package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JsonEmptyContainerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyCompleteEmptyContainersHaveAnEmptyLiteral() {
        for ((source, expected) in listOf("[]" to "[]", "{}" to "{}")) {
            assertEquals(expected, StreamingJsonParser.parse(source).root?.emptyContainerLiteral)
        }
        for (source in listOf("[", "{", "[1]", "{\"a\":1}", "null", "\"\"")) {
            assertNull(source, StreamingJsonParser.parse(source).root?.emptyContainerLiteral)
        }
    }

    @Test fun nativeRootAndNestedEmptyContainersUseNoContentWithoutDuplicateChildBlocks() {
        val source = mutableStateOf("[]")
        val language = mutableStateOf("en")
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            val config = app.appLanguageResources(language.value).configuration
            CompositionLocalProvider(LocalContext provides app.createConfigurationContext(config)) {
                MaterialTheme { JsonOrPlainView(source.value) }
            }
        }
        for ((locale, label) in listOf("en" to "No content", "zh" to "\u65e0\u5185\u5bb9")) {
            compose.runOnIdle { language.value = locale }
            assertEquals(label, app.appLanguageResources(locale).getString(R.string.tool_json_empty_container))
            for (literal in listOf("[]", "{}")) {
                compose.runOnIdle { source.value = literal }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText(label).assertIsDisplayed()
                compose.onAllNodesWithText(literal, substring = true).assertCountEquals(0)
                compose.onAllNodesWithText("(empty)", substring = true).assertCountEquals(0)
            }
            compose.runOnIdle { source.value = "{\"array\":[],\"object\":{}}" }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("array").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithText(label).assertCountEquals(2)
            compose.onAllNodesWithText("[\u2026]").assertCountEquals(0)
            compose.onAllNodesWithText("{\u2026}").assertCountEquals(0)
            compose.runOnIdle { source.value = "[[],{}]" }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("2").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithText(label).assertCountEquals(2)
            for (prefix in listOf("[", "{")) {
                compose.runOnIdle { source.value = prefix }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()
                }
                compose.onAllNodesWithText(label).assertCountEquals(0)
            }
            compose.runOnIdle { source.value = "[1]" }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("1").fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithText("1").assertCountEquals(2)
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }
}
