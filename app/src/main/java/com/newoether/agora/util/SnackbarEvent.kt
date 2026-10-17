package com.newoether.agora.util

import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.SnackbarDuration
import androidx.compose.ui.platform.AccessibilityManager

data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

internal fun snackbarTimeoutMillis(
    visuals: SnackbarVisuals,
    accessibilityManager: AccessibilityManager?
): Long {
    val durationMillis = when (visuals.duration) {
        SnackbarDuration.Short -> 4000L
        SnackbarDuration.Long -> 10000L
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
    }
    if (durationMillis == Long.MAX_VALUE) return durationMillis
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = durationMillis,
        containsIcons = true,
        containsText = true,
        containsControls = visuals.actionLabel != null
    ) ?: durationMillis
}
