package com.newoether.agora.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies a shell-confirmation decision made from [ShellConfirmationNotifier]'s notification.
 *
 * The notification only mirrors the pending prompt, so the decision must reach the same controller
 * the dialog uses. [ShellConfirmationNotifier.ACTION_ALLOW] and
 * [ShellConfirmationNotifier.ACTION_DENY] are the only accepted actions and both carry the prompt
 * id they were created for, which the controller checks before answering.
 */
class ShellConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val allow = when (intent.action) {
            ShellConfirmationNotifier.ACTION_ALLOW -> true
            ShellConfirmationNotifier.ACTION_DENY -> false
            else -> return
        }
        val promptId = intent.getLongExtra(ShellConfirmationNotifier.EXTRA_PROMPT_ID, NO_PROMPT)
        if (promptId == NO_PROMPT) return
        val sessionId = intent.getStringExtra(ShellConfirmationNotifier.EXTRA_SESSION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = withTimeoutOrNull(CONTAINER_WAIT_MS) {
                    (context.applicationContext as? AgoraApplication)?.awaitContainer()
                }
                if (container == null) {
                    DebugLog.w(TAG, "Dropped notification decision: confirmation queue unavailable")
                    return@launch
                }
                val controller = container.shellConfirmationController
                if (controller.notificationSessionId == sessionId) controller.resolve(promptId, allow)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Failed to apply notification decision", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ShellConfirmationReceiver"
        const val NO_PROMPT = 0L
        const val CONTAINER_WAIT_MS = 5_000L
    }
}
