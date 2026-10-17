package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.normalizedServiceTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConversationSettingsWrite(
    val conversationId: String,
    val settings: ConversationSettings?,
    val version: Long,
)

/**
 * Owns the immediately visible per-conversation settings while DataStore catches up.
 * Pending conversation IDs keep their optimistic value when an older persisted map arrives.
 */
internal class ConversationSettingsState {
    private val lock = Any()
    private val mutableState = MutableStateFlow<Map<String, ConversationSettings>>(emptyMap())
    private val pendingVersions = mutableMapOf<String, Long>()
    private var nextVersion = 0L

    val state: StateFlow<Map<String, ConversationSettings>> = mutableState.asStateFlow()

    fun set(
        conversationId: String,
        settings: ConversationSettings?,
    ): ConversationSettingsWrite = synchronized(lock) {
        createWrite(conversationId, settings)
    }

    fun update(
        conversationId: String,
        transform: (ConversationSettings) -> ConversationSettings,
    ): ConversationSettingsWrite = synchronized(lock) {
        createWrite(
            conversationId = conversationId,
            settings = transform(mutableState.value[conversationId] ?: ConversationSettings()),
        )
    }

    fun applyImport(
        imported: Map<String, ConversationSettings>,
        replace: Boolean,
    ): Map<String, ConversationSettings> = synchronized(lock) {
        val normalized = imported.filterValues { !it.isAllNull() }
            .mapValues { (_, value) -> value.normalizedServiceTier() }
        if (replace) {
            pendingVersions.clear()
        } else {
            imported.keys.forEach(pendingVersions::remove)
        }
        val updated = if (replace) {
            normalized
        } else {
            mutableState.value - imported.keys + normalized
        }
        mutableState.value = updated
        updated
    }

    fun isLatest(write: ConversationSettingsWrite): Boolean = synchronized(lock) {
        pendingVersions[write.conversationId] == write.version
    }

    fun acceptPersisted(settings: Map<String, ConversationSettings>) {
        synchronized(lock) {
            mutableState.value = mergePending(settings)
        }
    }

    fun complete(
        write: ConversationSettingsWrite,
        persisted: Map<String, ConversationSettings>,
    ) {
        synchronized(lock) {
            if (pendingVersions[write.conversationId] == write.version) {
                pendingVersions.remove(write.conversationId)
            }
            mutableState.value = mergePending(persisted)
        }
    }

    fun fail(
        write: ConversationSettingsWrite,
        persisted: Map<String, ConversationSettings>?,
    ) {
        synchronized(lock) {
            if (pendingVersions[write.conversationId] == write.version) {
                pendingVersions.remove(write.conversationId)
            }
            if (persisted != null) {
                mutableState.value = mergePending(persisted)
            }
        }
    }

    private fun createWrite(
        conversationId: String,
        settings: ConversationSettings?,
    ): ConversationSettingsWrite {
        val normalized = settings?.normalizedServiceTier()
            ?.takeUnless(ConversationSettings::isAllNull)
        val updated = mutableState.value.toMutableMap()
        if (normalized == null) {
            updated.remove(conversationId)
        } else {
            updated[conversationId] = normalized
        }
        mutableState.value = updated
        val version = ++nextVersion
        pendingVersions[conversationId] = version
        return ConversationSettingsWrite(conversationId, normalized, version)
    }

    private fun mergePending(
        persisted: Map<String, ConversationSettings>,
    ): Map<String, ConversationSettings> {
        if (pendingVersions.isEmpty()) return persisted
        val merged = persisted.toMutableMap()
        pendingVersions.keys.forEach { conversationId ->
            val optimistic = mutableState.value[conversationId]
            if (optimistic == null) {
                merged.remove(conversationId)
            } else {
                merged[conversationId] = optimistic
            }
        }
        return merged
    }
}
