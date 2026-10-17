package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemeTest {
    @Test
    fun everyPresetMapsAllMaterialRolesFromItsOwnGeneratedPalette() = forEachScheme { actual, generated, _ ->
        val roles = roleValues(actual)
        assertEquals(48, roles.size)
        roles.forEach { (getter, color) ->
            val expectedArgb = DynamicScheme::class.java.getMethod(getter).invoke(generated) as Int
            assertEquals("${generated.variant}: $getter", expectedArgb, color.toArgb())
        }
    }

    @Test
    fun amoledUsesBothEndpointsAndPreservesEveryOtherColorRole() = forEachScheme { base, _, dark ->
        assertSame(base, base.withAmoledBackground(dark, enabled = false))
        val actual = base.withAmoledBackground(dark, enabled = true)
        val endpoint = if (dark) Color.Black else Color.White
        assertEquals(endpoint, actual.background)
        assertEquals(endpoint, actual.surface)
        assertEquals(if (dark) endpoint else base.surfaceDim, actual.surfaceDim)
        assertEquals(if (dark) base.surfaceBright else endpoint, actual.surfaceBright)
        assertTrue(actual.surfaceDim.luminance() <= actual.surface.luminance())
        assertTrue(actual.surface.luminance() <= actual.surfaceBright.luminance())
        val changedRoles = setOf("getBackground", "getSurface", "getSurfaceDim", "getSurfaceBright")
        val actualRoles = roleValues(actual)
        roleValues(base).filterKeys { it !in changedRoles }.forEach { (role, color) ->
            assertEquals("AMOLED must retain $role", color, actualRoles.getValue(role))
        }
        assertEquals(roleValues(actual), roleValues(actual.withAmoledBackground(dark, enabled = true)))
    }

    @Test
    fun readableRolePairsMeetSmallTextContrastWithAndWithoutAmoled() = forEachScheme { base, _, dark ->
        listOf(base, base.withAmoledBackground(dark, enabled = true)).forEach { scheme ->
            with(scheme) {
                listOf(
                    primary to onPrimary,
                    primaryContainer to onPrimaryContainer,
                    secondary to onSecondary,
                    secondaryContainer to onSecondaryContainer,
                    tertiary to onTertiary,
                    tertiaryContainer to onTertiaryContainer,
                    error to onError,
                    errorContainer to onErrorContainer,
                    background to onBackground,
                    surface to onSurface,
                    surfaceVariant to onSurfaceVariant,
                    surfaceContainer to onSurfaceVariant,
                    primaryContainer to error,
                ).forEach { (background, foreground) ->
                    val contrast = contrast(foreground, background)
                    assertTrue("$foreground on $background: $contrast", contrast >= 4.5f)
                }
            }
        }
    }

    @Test
    fun forestContainersDoNotInheritTheMaterialBaselinePalette() {
        val light = colorSchemeForPreset(ColorSchemePreset.FOREST)
        val dark = colorSchemeForPreset(ColorSchemePreset.FOREST, isDark = true)
        assertEquals(Color(0xFFECEFE6), light.surfaceContainer)
        assertEquals(Color(0xFF1D211B), dark.surfaceContainer)
    }

    private fun roleValues(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { getter ->
                getter.name.substringBefore('-') to Color((getter.invoke(scheme) as Long).toULong())
            }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private fun forEachScheme(check: (ColorScheme, DynamicScheme, Boolean) -> Unit) {
        val seeds = mapOf(
            ColorSchemePreset.MIDNIGHT to 0xFF1A237E,
            ColorSchemePreset.NORDIC to 0xFF546E7A,
            ColorSchemePreset.FOREST to 0xFF2E7D32,
            ColorSchemePreset.SUNSET to 0xFFE65100,
            ColorSchemePreset.ROSE to 0xFFAD1457,
            ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
            ColorSchemePreset.SLATE to 0xFF455A64,
            ColorSchemePreset.OCEAN to 0xFF0277BD,
        )
        seeds.forEach { (preset, seed) ->
            SchemeStyle.entries.forEach { style ->
                listOf(false, true).forEach { dark ->
                    val hct = Hct.fromInt(seed.toInt())
                    val generated = when (style) {
                        SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0)
                        SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
                        SchemeStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0)
                        SchemeStyle.NEUTRAL -> SchemeNeutral(hct, dark, 0.0)
                    }
                    check(colorSchemeForPreset(preset, style, dark), generated, dark)
                }
            }
        }
    }
}
