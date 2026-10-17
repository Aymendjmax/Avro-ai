package com.newoether.agora.webui

/** The app font the browser should load, mirroring the Appearance font preference. */
internal sealed interface WebUiFont {
    /** The bundled Mi Outfit variable font. */
    data object AppDefault : WebUiFont

    /** The device font; the browser uses its own system font. */
    data object System : WebUiFont

    /** A user-imported font file inside app storage. */
    data class Custom(val path: String) : WebUiFont
}

/**
 * The app's current resolved Material color scheme (preset or wallpaper colors, light or dark,
 * AMOLED) and font, published by the Compose theme so the browser looks like the app.
 *
 * [colors] maps Material role names (`primary`, `onSurfaceVariant`, ...) to ARGB values.
 */
internal data class WebUiTheme(
    val dark: Boolean,
    val colors: Map<String, Int>,
    val font: WebUiFont,
) {
    /** CSS custom properties consumed by `style.css`: `--md-<role>` and `--app-font`. */
    fun toCss(): String = buildString {
        if (font != WebUiFont.System) {
            // One face per weight the Compose FontFamily registers (OutfitFamily and the custom
            // family both list exactly these). CSS font matching then resolves a requested weight
            // the way Compose's FontMatcher does - SemiBold (600) lands on the 700 face - and a
            // variable font's wght axis is clamped to that face's single weight, so the browser
            // never draws a weight the phone cannot.
            for (weight in COMPOSE_FAMILY_WEIGHTS) {
                append("@font-face{font-family:\"AgoraApp\";src:url(\"$FONT_PATH?v=")
                append(fontVersion())
                append("\");font-weight:").append(weight).append(";font-display:swap}\n")
            }
        }
        append(":root{color-scheme:")
        append(if (dark) "dark" else "light")
        append(';')
        colors.toSortedMap().forEach { (role, argb) ->
            append("--md-").append(role.toKebabCase()).append(':').append(argb.toCssHex()).append(';')
        }
        append("--app-font:")
        append(if (font == WebUiFont.System) SYSTEM_STACK else "\"AgoraApp\",$SYSTEM_STACK")
        append("}\n")
    }

    /** Changes when the font source changes, so the browser does not reuse a cached font. */
    private fun fontVersion(): String = when (font) {
        WebUiFont.AppDefault -> "app"
        WebUiFont.System -> "system"
        is WebUiFont.Custom -> Integer.toHexString(font.path.hashCode())
    }

    companion object {
        const val FONT_PATH = "/fonts/app"

        /** ExtraLight, Light, Normal, Medium and Bold: the entries of OutfitFamily in Type.kt. */
        internal val COMPOSE_FAMILY_WEIGHTS = listOf(200, 300, 400, 500, 700)
        private const val SYSTEM_STACK = "system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif"

        private fun String.toKebabCase(): String =
            replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

        /** `#rrggbb`, or `#rrggbbaa` when not opaque. */
        internal fun Int.toCssHex(): String {
            val rgb = String.format("#%06x", this and 0xFFFFFF)
            val alpha = (this ushr 24) and 0xFF
            return if (alpha == 0xFF) rgb else rgb + String.format("%02x", alpha)
        }
    }
}
