package com.newoether.agora.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentCaptionRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()


    @Test
    fun openingAFileDoesNotClipItsCaption() {
        val fileName = "agora-diagnostics-redacted-json.json"
        var opened: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.width(72.dp).background(Color.White).testTag("passive")) {
                        AttachmentThumbnailItem(type = "file", imagePath = "", fileName = fileName)
                    }
                    Box(Modifier.width(72.dp).background(Color.White).testTag("openable")) {
                        AttachmentThumbnailItem(
                            type = "file",
                            imagePath = "",
                            fileName = fileName,
                            textContent = "fixture content",
                            handlers = ThumbnailClickHandlers(onFileClick = { name, content ->
                                opened = name to content
                            }),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val passive = compose.onNodeWithTag("passive").fetchSemanticsNode().boundsInRoot
        val openable = compose.onNodeWithTag("openable").fetchSemanticsNode().boundsInRoot
        val caption = compose.onAllNodes(hasText(fileName), useUnmergedTree = true)[0]
            .fetchSemanticsNode().boundsInRoot
        assertEquals(passive.width, openable.width, 0f)
        assertEquals(passive.height, openable.height, 0f)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        try {
            var captionInk = 0
            for (y in caption.top.toInt() until caption.bottom.toInt()) {
                for (x in caption.left.toInt() until caption.right.toInt()) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) captionInk++
                }
            }
            assertTrue("The filename must render visible text", captionInk > 0)
            for (y in 0 until passive.height.toInt()) {
                for (x in 0 until passive.width.toInt()) {
                    assertEquals(
                        "file presentation pixel ($x,$y)",
                        bitmap.getPixel(passive.left.toInt() + x, passive.top.toInt() + y),
                        bitmap.getPixel(openable.left.toInt() + x, openable.top.toInt() + y),
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
        compose.onAllNodes(hasText(fileName), useUnmergedTree = true)[1]
            .performClick()
        compose.waitForIdle()
        assertEquals(fileName to "fixture content", opened)
    }
}
