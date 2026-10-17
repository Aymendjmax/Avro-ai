package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates user confirmation of shell commands issued by remote MCP / shell servers.
 *
 * Owns the pending-confirmation [StateFlow], the per-session trust set, and the
 * suspend/await handshake between the generation pipeline (which asks) and the UI
 * (which answers). Extracted from [ChatViewModel] as a single responsibility so the
 * trust policy lives in one place and is independently testable.
 */
class ShellConfirmationController internal constructor(
    private val confirmEnabled: StateFlow<Boolean>,
    private val persistEnabled: (Boolean) -> Unit,
) {
    /**
     * [id] identifies this exact prompt. Answers carry it so a late reply for a prompt that has
     * already been answered or timed out can never resolve the prompt now on screen.
     */
    data class PendingShellCommand(
        val id: Long,
        val server: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>,
        /** Conversation whose tool call is waiting; a notification tap opens it. */
        val conversationId: String? = null,
    )
    val notificationSessionId: String = java.util.UUID.randomUUID().toString()

    constructor(settings: SettingsRepository) : this(
        confirmEnabled = settings.shellConfirmEnabled,
        persistEnabled = { enabled -> settings.setShellConfirmEnabled(enabled) },
    )

    private val _pendingShellCommand = MutableStateFlow<PendingShellCommand?>(null)
    val pendingShellCommand: StateFlow<PendingShellCommand?> = _pendingShellCommand.asStateFlow()

    // Servers the user chose to trust for the rest of this app session.
    private val sessionAllowedServers = Collections.synchronizedSet(mutableSetOf<String>())

    private val nextPromptId = AtomicLong(1)

    // One prompt on screen at a time. Without this, parallel conversations overwrite each
    // other's pending prompt: the loser's dialog never renders and its confirm() silently
    // times out refused.
    private val promptMutex = Mutex()

    /** Suspends until the user resolves the prompt; returns whether the command may run. */
    suspend fun confirm(server: String, summary: String, conversationId: String? = null): Boolean {
        if (!confirmEnabled.value) return true
        if (sessionAllowedServers.contains(server)) return true
        return promptMutex.withLock {
            // Re-check after the wait — the user may have trusted this server while an
            // earlier conversation's prompt was up.
            if (sessionAllowedServers.contains(server)) return@withLock true
            val deferred = CompletableDeferred<Boolean>()
            val promptId = nextPromptId.getAndIncrement()
            _pendingShellCommand.value = PendingShellCommand(promptId, server, summary, deferred, conversationId)
            try {
                // Backgrounding the app does not answer the request. There is no visible dialog
                // there, so the app layer re-surfaces the wait as a notification with the same
                // Allow/Deny actions; the command runs only on an explicit answer. The timeout is
                // the final bound so an unanswered request cannot hold the tool loop forever, and
                // Activity recreation keeps this process-scoped prompt alive as before.
                withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                false
            } finally {
                val pending = _pendingShellCommand.value
                if (pending?.deferred === deferred) {
                    _pendingShellCommand.compareAndSet(pending, null)
                }
            }
        }
    }

    /**
     * Called by the UI or by a notification action to answer a pending confirmation. A [promptId]
     * that is no longer pending is ignored, so a prompt can never be answered twice and a stale
     * answer can never decide a newer request.
     */
    fun resolve(promptId: Long, allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingShellCommand.value ?: return
        if (pending.id != promptId) return
        if (!_pendingShellCommand.compareAndSet(pending, null)) return
        if (allow && alwaysAllowServer) sessionAllowedServers.add(pending.server)
        pending.deferred.complete(allow)
    }

    fun setEnabled(enabled: Boolean) = persistEnabled(enabled)
}
