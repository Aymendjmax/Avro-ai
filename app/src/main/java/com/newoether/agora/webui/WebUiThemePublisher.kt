package com.newoether.agora.webui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Hands the resolved app theme to the WebUI. Call it inside `AgoraTheme` so it sees the same
 * [MaterialTheme.colorScheme] as the app; it republishes whenever the scheme or font changes.
 */
@Composable
internal fun PublishWebUiTheme(webUi: WebUiController, fontPreference: String, customFontPath: String) {
    val scheme = MaterialTheme.colorScheme
    // AgoraTheme remembers its scheme, so this rebuilds only when the theme or font changes.
    val theme = remember(scheme, fontPreference, customFontPath) {
        WebUiTheme(
            dark = scheme.background.luminance() < 0.5f,
            colors = scheme.webRoles(),
            font = when (fontPreference) {
                "system" -> WebUiFont.System
                "custom" -> WebUiFont.Custom(customFontPath)
                else -> WebUiFont.AppDefault
            },
        )
    }
    SideEffect { webUi.publishTheme(theme) }
}

internal fun ColorScheme.webRoles(): Map<String, Int> = mapOf<String, Color>(
    "primary" to primary,
    "secondary" to secondary,
    "tertiary" to tertiary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "error" to error,
    "onError" to onError,
    "errorContainer" to errorContainer,
    "onErrorContainer" to onErrorContainer,
    "inverseSurface" to inverseSurface,
    "inverseOnSurface" to inverseOnSurface,
).mapValues { (_, color) -> color.toArgb() }
