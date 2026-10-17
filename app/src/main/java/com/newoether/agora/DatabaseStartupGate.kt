package com.newoether.agora

import com.newoether.agora.data.local.DatabaseCompatibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface DatabaseStartupState {
    data object Checking : DatabaseStartupState
    data object Ready : DatabaseStartupState

    data class Blocked(
        val reason: DatabaseStartupBlockReason,
    ) : DatabaseStartupState
}

sealed interface DatabaseStartupBlockReason {
    data class FutureVersion(
        val storedVersion: Int,
        val supportedVersion: Int,
    ) : DatabaseStartupBlockReason

    data class Unreadable(
        val errorType: String,
    ) : DatabaseStartupBlockReason
}

/**
 * Serializes the process database lifecycle.
 *
 * No resource is published before it has opened successfully and passed Room's schema
 * validation. The only destructive transition is [clearBlockedDatabase], which is
 * rejected unless startup is already blocked and no resource is retained.
 */
internal class DatabaseStartupGate<T : Any>(
    private val inspectDatabase: suspend () -> DatabaseCompatibility,
    private val openResource: suspend () -> T,
    private val closeResource: (T) -> Unit,
    private val deleteDatabase: suspend () -> Boolean,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<DatabaseStartupState>(
        DatabaseStartupState.Checking,
    )
    private var resource: T? = null

    val state: StateFlow<DatabaseStartupState> = mutableState.asStateFlow()

    suspend fun initialize(): DatabaseStartupState = lifecycleMutex.withLock {
        initializeLocked()
    }

    suspend fun awaitState(): DatabaseStartupState =
        state.first { it !is DatabaseStartupState.Checking }

    suspend fun awaitReadyResource(): T? =
        if (awaitState() is DatabaseStartupState.Ready) resource else null

    fun requireReadyResource(): T {
        check(state.value is DatabaseStartupState.Ready) {
            "Database-backed services are unavailable while startup is " + state.value
        }
        return checkNotNull(resource) {
            "Database startup is Ready without a process resource"
        }
    }

    suspend fun clearBlockedDatabase(): Boolean = lifecycleMutex.withLock {
        if (mutableState.value !is DatabaseStartupState.Blocked || resource != null) {
            return@withLock false
        }

        val deleted = try {
            deleteDatabase()
        } catch (error: Exception) {
            reportFailure(error)
            false
        }
        if (!deleted) return@withLock false

        mutableState.value = DatabaseStartupState.Checking
        initializeLocked() is DatabaseStartupState.Ready
    }

    private suspend fun initializeLocked(): DatabaseStartupState {
        if (mutableState.value !is DatabaseStartupState.Checking) {
            return mutableState.value
        }

        val compatibility = try {
            inspectDatabase()
        } catch (error: Exception) {
            reportFailure(error)
            DatabaseCompatibility.Unreadable(error)
        }

        when (compatibility) {
            is DatabaseCompatibility.FutureVersion -> {
                return block(
                    DatabaseStartupBlockReason.FutureVersion(
                        storedVersion = compatibility.storedVersion,
                        supportedVersion = compatibility.supportedVersion,
                    )
                )
            }
            is DatabaseCompatibility.Unreadable -> {
                reportFailure(compatibility.error)
                return block(
                    DatabaseStartupBlockReason.Unreadable(
                        errorType = compatibility.error.javaClass.simpleName,
                    )
                )
            }
            DatabaseCompatibility.Missing,
            is DatabaseCompatibility.Supported -> Unit
        }

        val opened = try {
            openResource()
        } catch (error: Exception) {
            reportFailure(error)
            return block(
                DatabaseStartupBlockReason.Unreadable(
                    errorType = error.javaClass.simpleName,
                )
            )
        }

        resource = opened
        mutableState.value = DatabaseStartupState.Ready
        return DatabaseStartupState.Ready
    }

    private fun block(reason: DatabaseStartupBlockReason): DatabaseStartupState.Blocked =
        DatabaseStartupState.Blocked(reason).also { mutableState.value = it }
}
