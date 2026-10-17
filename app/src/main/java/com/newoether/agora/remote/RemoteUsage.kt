package com.newoether.agora.remote

import kotlinx.serialization.Serializable

@Serializable internal data class RemoteUsage(val limits: List<RemoteUsageLimit>)
@Serializable internal data class RemoteUsageLimit(
    val id: String, val name: String, val primary: RemoteUsageWindow? = null, val secondary: RemoteUsageWindow? = null,
)
@Serializable internal data class RemoteUsageWindow(
    val usedPercent: Double, val windowDurationMins: Long? = null, val resetsAt: Long? = null,
) {
    val remainingPercent: Int get() = (100.0 - usedPercent).coerceIn(0.0, 100.0).toInt()
}
