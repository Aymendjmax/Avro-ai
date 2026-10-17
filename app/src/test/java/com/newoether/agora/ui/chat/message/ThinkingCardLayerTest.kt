package com.newoether.agora.ui.chat.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.gradientBlur
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThinkingCardLayerTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun surfaceLocalDisclosureStaysBelowParentTopGradientWithBlurOff() {
        assertDisclosureBelowTopGradient(blurAtTopDp = 0f)
    }

    @Test
    fun surfaceLocalDisclosureStaysBelowParentTopGradientWithBlurOn() {
        assertDisclosureBelowTopGradient(blurAtTopDp = 8f)
    }

    private fun assertDisclosureBelowTopGradient(blurAtTopDp: Float) {
        val covered = mutableStateOf(false)
        val expanded = mutableStateMapOf("g" to true)
        val appearances = SegmentAppearanceRegistry()
        val segments = listOf(MessageSegment("thought", "Reasoning content"))
        val message = ChatMessage(id = "m", text = "", participant = Participant.MODEL, segments = segments)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    Box(Modifier.width(328.dp).height(220.dp).background(Color.Black).testTag("viewport")) {
                        Box(Modifier.fillMaxSize().gradientBlur(
                            blurAtTopDp = blurAtTopDp,
                            blurAtBottomDp = 0f,
                            fadeHeightDp = 40f,
                            bottomOverlayHeight = 40.dp,
                        )) {
                            CompactSegmentBlock(
                                segs = segments, segmentIndices = listOf(0), message = message,
                                isStreaming = false, useLiveStatus = false, generationActive = false,
                                isCurrentCard = true, expandedStates = expanded, expansionKey = "g",
                                segmentAppearanceRegistry = appearances, onSegmentClick = {},
                                modifier = Modifier.testTag("card"),
                            )
                        }
                        if (covered.value) Box(Modifier.width(328.dp).height(180.dp).zIndex(1.5f)
                            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .98f),
                                .6f to Color.Black.copy(alpha = .8f), 1f to Color.Transparent)))
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val region = android.graphics.Rect((bounds.right - 22).toInt(), (bounds.top + 18).toInt(),
            (bounds.right - 4).toInt(), (bounds.top + 36).toInt())
        fun capture(): Bitmap {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            return bitmap
        }
        val before = capture()
        compose.runOnIdle { covered.value = true }
        compose.waitForIdle()
        val after = capture()
        try {
            fun brightness(bitmap: Bitmap): Int {
                var total = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    val pixel = bitmap.getPixel(x, y)
                    total += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                }
                return total
            }
            val visible = brightness(before)
            val obscured = brightness(after)
            assertTrue("Disclosure region must contain painted pixels: $visible", visible > 0)
            assertTrue("Top gradient must cover the complete card-local arrow: $visible -> $obscured",
                obscured < visible / 3)
            println("THINKING_LAYER arrow=$region before=$visible after=$obscured")
        } finally { before.recycle(); after.recycle() }
    }
}
