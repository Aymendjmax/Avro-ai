package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Thought Markdown shares the answer LaTeX pipeline, so a formula never leaks its latex:// URL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThoughtLatexRenderingTest {
    @get:Rule val compose = createComposeRule()

    // The app initialises jLatexMath through its manifest; Robolectric tests must do it here.
    @Before fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    @Test fun thoughtInlineFormulaRendersAsImage() = verify(thought = true)

    @Test fun answerInlineFormulaRendersAsImage() = verify(thought = false)

    private fun verify(thought: Boolean) {
        val inlineImages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val blockImages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val content = "So the ratio is \\(\\frac{a+b}{c^2}\\) and the sum \\(\\sum_{i=1}^{n} x_i\\) follows."
        compose.setContent {
            MaterialTheme {
                val assets = rememberChatMarkdownAssets(textColor = MaterialTheme.colorScheme.onSurface)
                val context = if (thought) assets.thoughtRenderContext else assets.renderContext
                val components = markdownComponents(
                    text = assets.components.text,
                    paragraph = assets.components.paragraph,
                    image = { model -> blockImages += model.node.startOffset; ScrollableDisplayLatexImage(model) },
                    inlineImage = { model -> inlineImages += model.content; ChatMarkdownInlineImage(model) },
                )
                IncrementalStreamingMarkdownContent(
                    content = content,
                    isStreaming = false,
                    renderContext = ChatMarkdownRenderContext(
                        context.colors, context.typography, context.padding, components,
                        context.annotator, context.imageTransformer, context.flavour,
                        context.plainTextStyle, context.parseInlineDollarMath,
                    ),
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        // Formula bitmaps render off the main thread; give every frame a chance to settle.
        repeat(20) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.waitForIdle()
        }
        val texts = compose.onAllNodes(isRoot().not(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
        assertTrue("No text rendered: $texts", texts.any { "ratio" in it })
        // Semantics always carry the inline alternate text; what matters is that every formula
        // is drawn as an image, inline or promoted to a block once its bitmap is taller than a
        // line, instead of as that text. The renderer hands an inline image's alternate text (the
        // formula source) to the image transformer, so each formula must resolve from it.
        assertTrue("No image slots: inline=$inlineImages block=$blockImages", inlineImages.isNotEmpty())
        val drawn = compose.onAllNodes(isRoot().not(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("Formulas not drawn: $drawn", "\\frac{a+b}{c^2}" in drawn && "\\sum_{i=1}^{n} x_i" in drawn)
    }
}
