package com.newoether.agora.sandbox

import android.content.Context
import com.newoether.agora.data.repository.SettingsRepository

class FdroidSandboxManagerFactory(
    private val context: Context,
    private val settings: SettingsRepository,
) : SandboxManagerFactory {
    // The AppContainer-owned factory keeps one ProotSandboxManager for the process. ChatViewModel,
    // GenerationManager, and TaskExecutionEngine borrow it; their shorter lifecycles must not stop
    // the shared scope. One instance also lets the internal mutex serialize every mutation of the
    // process-global Alpine rootfs and prevent cross-conversation package database corruption.
    private val shared by lazy { ProotSandboxManager(context, settings) }
    override fun create(): SandboxManager = shared
    override fun isAvailable(): Boolean = true
}
