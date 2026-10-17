package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.components.renderLatexToBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A formula on its own line is drawn, not left as an empty selectable placeholder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayLatexRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    @Test fun inlineMatrixDrawsThroughCompose() = verifyMatrix(display = false, streamed = false)
    @Test fun displayMatrixDrawsThroughCompose() = verifyMatrix(display = true, streamed = false)
    @Test fun inlineMatrixSurvivesStreamingSettlement() = verifyMatrix(display = false, streamed = true)
    @Test fun displayMatrixSurvivesStreamingSettlement() = verifyMatrix(display = true, streamed = true)

    private fun verifyMatrix(display: Boolean, streamed: Boolean) {
        val matrix = if (display) {
            """\begin{pmatrix}
2 & -1 & 4 \\
0 & 3 & 7 \\
5 & 6 & -2
\end{pmatrix}"""
        } else {
            """\begin{pmatrix} 2 & -1 & 4 \\ 0 & 3 & 7 \\ 5 & 6 & -2 \end{pmatrix}"""
        }
        val source = if (display) "\$\$\n$matrix\n\$\$" else "\$$matrix\$"
        val input = mutableStateOf(source to streamed)
        var textSize = 0f
        compose.setContent {
            MaterialTheme {
                textSize = with(LocalDensity.current) { 20.sp.toPx() }
                val assets = rememberChatMarkdownAssets(
                    textColor = MaterialTheme.colorScheme.onSurface,
                    parseInlineDollarMath = true,
                )
                IncrementalStreamingMarkdownContent(
                    content = input.value.first,
                    isStreaming = input.value.second,
                    renderContext = assets.renderContext,
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        compose.waitForIdle()
        val expected = requireNotNull(renderLatexToBitmap(matrix, textSize = textSize))
        try {
            for (stage in if (streamed) listOf("streaming", "terminal") else listOf("terminal")) {
                if (stage == "terminal" && streamed) {
                    compose.runOnIdle { input.value = source to false }
                }
                repeat(20) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(50)
                    compose.waitForIdle()
                }
                val bounds = compose.onNode(hasContentDescription(matrix), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("$stage matrix width $bounds vs ${expected.width}",
                    kotlin.math.abs(bounds.width - expected.width) <= 1f)
                assertTrue("$stage matrix height $bounds vs ${expected.height}",
                    kotlin.math.abs(bounds.height - expected.height) <= 1f)
                val view = compose.activity.window.decorView
                val screenshot = android.graphics.Bitmap.createBitmap(
                    view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
                )
                try {
                    compose.runOnUiThread { view.draw(android.graphics.Canvas(screenshot)) }
                    val left = bounds.left.toInt().coerceIn(0, screenshot.width - 1)
                    val top = bounds.top.toInt().coerceIn(0, screenshot.height - 1)
                    val right = bounds.right.toInt().coerceIn(left + 1, screenshot.width)
                    val bottom = bounds.bottom.toInt().coerceIn(top + 1, screenshot.height)
                    val background = screenshot.getPixel(right - 1, top)
                    var ink = 0
                    for (y in top until bottom) for (x in left until right) {
                        if (screenshot.getPixel(x, y) != background) ink++
                    }
                    assertTrue("$stage Compose matrix must draw ink, bounds=$bounds", ink > 0)
                    println("COMPOSEMATRIX display=$display stage=$stage bounds=$bounds ink=$ink")
                } finally {
                    screenshot.recycle()
                }
            }
        } finally {
            expected.recycle()
        }
    }

    @Test fun displayFormulaDrawsInk() {
        val content = "Before\n\n\$\$\nD=\\sqrt{a^2+b^2+c^2}\n\$\$\n\nAfter"
        compose.setContent {
            MaterialTheme {
                val assets = rememberChatMarkdownAssets(textColor = MaterialTheme.colorScheme.onSurface)
                IncrementalStreamingMarkdownContent(
                    content = content,
                    isStreaming = false,
                    renderContext = assets.renderContext,
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        repeat(20) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.waitForIdle()
        }
        val node = compose.onNode(hasContentDescription("D=\\sqrt{a^2+b^2+c^2}"), useUnmergedTree = true)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        fun ink(top: Int, bottom: Int, right: Int): Int {
            val bg = bitmap.getPixel(right - 1, top)
            var n = 0
            for (x in 0 until right) for (y in top until bottom) if (bitmap.getPixel(x, y) != bg) n++
            return n
        }
        val formulaInk = ink(bounds.top.toInt(), bounds.bottom.toInt(), bounds.right.toInt())
        assertTrue("multi-line display formula must be drawn, bounds=$bounds", formulaInk > 0)
    }
}
