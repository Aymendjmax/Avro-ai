package com.newoether.agora.ui.chat.message

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * User bubble outline: top-start, top-end and bottom-start share one radius, bottom-end is the tail.
 *
 * RoundedCornerShape shrinks each side's corner pair on its own when the bubble is shorter or
 * narrower than two radii, so the start corners (27 + 27) would shrink while top-end (27 + 6)
 * kept its full size. Here the shared radius is clamped once to half the smaller dimension, so all
 * three large corners always match.
 */
internal data class UserBubbleShape(
    // Half of a single-line bubble: 15dp padding on each side plus one 24.2sp line.
    val cornerRadius: Dp = 27.dp,
    val tailRadius: Dp = 6.dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = userBubbleCornerRadiusPx(with(density) { cornerRadius.toPx() }, size)
        val tail = min(with(density) { tailRadius.toPx() }, radius)
        val large = CornerRadius(radius)
        val small = CornerRadius(tail)
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = large,
                topRight = large,
                bottomRight = if (ltr) small else large,
                bottomLeft = if (ltr) large else small,
            ),
        )
    }
}

/** Shared radius for the three large corners: the requested radius, capped at half the smaller side. */
internal fun userBubbleCornerRadiusPx(requestedPx: Float, size: Size): Float =
    min(requestedPx, size.minDimension / 2f).coerceAtLeast(0f)
