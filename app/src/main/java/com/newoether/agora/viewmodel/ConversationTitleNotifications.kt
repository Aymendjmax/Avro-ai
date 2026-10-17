package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository

/** Emits the foreground title outcome in the caller's existing ordered notification coroutine. */
internal suspend fun ConversationTitleGenerator.generateWithNotifications(
    conversationId: String,
    settings: SettingsRepository,
    appContext: Context,
    onSnackbarSuspend: suspend (String) -> Unit,
) {
    settings.awaitInitialLoad()
    if (settings.titleGenerationNotificationsEnabled.value) {
        onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
    }
    when (generateAndPersist(conversationId)) {
        is ConversationTitleGenerator.Result.Success -> {
            if (settings.titleGenerationNotificationsEnabled.value) {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
            }
        }
        is ConversationTitleGenerator.Result.Failure -> {
            if (settings.titleGenerationNotificationsEnabled.value) {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
            }
        }
    }
}
