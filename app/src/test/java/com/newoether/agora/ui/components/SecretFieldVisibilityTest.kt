package com.newoether.agora.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Every secret input is masked by default and has the shared eye button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SecretFieldVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startsHiddenAndTogglesWithTheEyeButton() {
        var visible = true
        compose.setContent {
            var state by rememberSecretVisible()
            visible = state
            SecretVisibilityToggle(state) { state = !state }
        }
        compose.waitForIdle()
        assertFalse(visible)
        compose.onNodeWithContentDescription("Show Value").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(visible)
        compose.onNodeWithContentDescription("Hide Value").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertFalse(visible)
    }

    @Test fun masksOnlyWhileHidden() {
        val value = AnnotatedString("sk-secret")
        assertEquals(VisualTransformation.None, secretVisualTransformation(true))
        val masked = secretVisualTransformation(false).filter(value).text.text
        assertEquals(PasswordVisualTransformation().filter(value).text.text, masked)
        assertFalse(masked.contains("secret"))
    }

    @Test fun everyMaskedFieldUsesTheSharedToggle() {
        val ui = File(sourceRoot(), "com/newoether/agora/ui")
        val sources = ui.walkTopDown().filter { it.extension == "kt" }.toList()
        val owner = "SecretFieldVisibility.kt"
        val stray = sources.filter { it.name != owner && it.readText().contains("PasswordVisualTransformation") }
        assertEquals("masking must go through $owner", emptyList<String>(), stray.map { it.name })
        sources.filter { it.name != owner && it.readText().contains("secretVisualTransformation(") }.forEach { file ->
            val text = file.readText()
            assertEquals(
                "${file.name}: each masked field needs its eye button",
                Regex("secretVisualTransformation\\(").findAll(text).count(),
                Regex("SecretVisibilityToggle\\(").findAll(text).count(),
            )
        }
    }

    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
}
