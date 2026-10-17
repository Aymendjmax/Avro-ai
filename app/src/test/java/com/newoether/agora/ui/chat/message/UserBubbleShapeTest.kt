package com.newoether.agora.ui.chat.message

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Outline.Rounded builds an android.graphics.Path for uneven corners, so this needs Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UserBubbleShapeTest {
    private val density = Density(1f)

    private fun rounded(size: Size, direction: LayoutDirection = LayoutDirection.Ltr) =
        (UserBubbleShape().createOutline(size, direction, density) as Outline.Rounded).roundRect

    @Test
    fun shortBubbleKeepsAllLargeCornersEqual() {
        // Slightly under two radii tall: the start pair must not shrink below top-end.
        val rect = rounded(Size(92f, 45f))
        assertEquals(22.5f, rect.topLeftCornerRadius.x, 0.001f)
        assertEquals(22.5f, rect.topRightCornerRadius.x, 0.001f)
        assertEquals(22.5f, rect.bottomLeftCornerRadius.x, 0.001f)
        assertEquals(6f, rect.bottomRightCornerRadius.x, 0.001f)
    }

    @Test
    fun narrowBubbleUsesHalfWidthForAllLargeCorners() {
        val rect = rounded(Size(40f, 48f))
        assertEquals(20f, rect.topLeftCornerRadius.x, 0.001f)
        assertEquals(20f, rect.topRightCornerRadius.x, 0.001f)
        assertEquals(20f, rect.bottomLeftCornerRadius.x, 0.001f)
        assertEquals(6f, rect.bottomRightCornerRadius.x, 0.001f)
    }

    @Test
    fun tallBubbleKeepsFixedRadius() {
        val rect = rounded(Size(200f, 300f))
        assertEquals(27f, rect.topLeftCornerRadius.x, 0.001f)
        assertEquals(27f, rect.topRightCornerRadius.x, 0.001f)
        assertEquals(27f, rect.bottomLeftCornerRadius.x, 0.001f)
        assertEquals(6f, rect.bottomRightCornerRadius.x, 0.001f)
    }

    @Test
    fun rtlMovesTailToBottomLeft() {
        val rect = rounded(Size(200f, 300f), LayoutDirection.Rtl)
        assertEquals(6f, rect.bottomLeftCornerRadius.x, 0.001f)
        assertEquals(27f, rect.bottomRightCornerRadius.x, 0.001f)
    }
}
