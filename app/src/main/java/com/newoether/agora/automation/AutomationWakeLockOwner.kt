package com.newoether.agora.automation

import android.content.Context
import android.os.PowerManager
import com.newoether.agora.util.DebugLog

internal fun interface AutomationWakeLockLeaseFactory {
    fun acquire(): AutoCloseable
}

/**
 * Owns the optional partial wake lock for one Task or Loop execution.
 *
 * The lease is deliberately scoped to the actual execution block. WorkManager already keeps its
 * workers awake; this user-controlled lease also covers the app's shared automation engine when it
 * is invoked from another foreground/background path.
 */
class AutomationWakeLockOwner internal constructor(
    private val leaseFactory: AutomationWakeLockLeaseFactory,
) {
    constructor(context: Context) : this(
        leaseFactory = AndroidAutomationWakeLockLeaseFactory(context.applicationContext),
    )

    suspend fun <T> whileHeld(
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        val lease = if (enabled) {
            runCatching(leaseFactory::acquire)
                .onFailure { error ->
                    DebugLog.e("AutomationWakeLock", "Unable to acquire automation wake lock", error)
                }
                .getOrNull()
        } else {
            null
        }
        return try {
            block()
        } finally {
            runCatching { lease?.close() }
                .onFailure { error ->
                    DebugLog.e("AutomationWakeLock", "Unable to release automation wake lock", error)
                }
        }
    }
}

private class AndroidAutomationWakeLockLeaseFactory(
    context: Context,
) : AutomationWakeLockLeaseFactory {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val tag = "${context.packageName}:automation"

    override fun acquire(): AutoCloseable {
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            acquire()
        }
        return AutoCloseable {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
