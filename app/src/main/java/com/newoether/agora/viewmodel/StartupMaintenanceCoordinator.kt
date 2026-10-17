package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Launches independent, one-shot maintenance after the conversation list is visible. */
internal class StartupMaintenanceCoordinator(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val currentVersion: () -> String,
    private val checkUpdate: suspend (String) -> UpdateInfo?,
    private val onUpdateFound: (UpdateInfo) -> Unit,
    private val startAutoBackup: () -> Unit,
    private val startSemanticIndex: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        scope.launch(ioDispatcher) { checkForUpdateIfDue() }
        startAutoBackup()
        startSemanticIndex()
    }

    private suspend fun checkForUpdateIfDue() {
        if (!settings.getAutoUpdateCheck()) return
        val checkedAt = now()
        if (checkedAt - settings.getLastUpdateCheckTime() <= UPDATE_INTERVAL_MS) return
        settings.saveLastUpdateCheckTime(checkedAt)
        checkUpdate(currentVersion())?.let(onUpdateFound)
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
