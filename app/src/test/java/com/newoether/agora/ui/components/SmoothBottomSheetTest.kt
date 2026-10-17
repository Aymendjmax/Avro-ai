package com.newoether.agora.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothBottomSheetTest {
    @Test
    fun `sheet geometry and spring match the established segment detail behavior`() {
        assertEquals(640.dp, BottomSheetMaxWidth)
        assertEquals(0f, SMOOTH_SHEET_HIDDEN_FRACTION, 0f)
        assertEquals(0.45f, SMOOTH_SHEET_PARTIAL_FRACTION, 0f)
        assertEquals(0.94f, SMOOTH_SHEET_EXPANDED_FRACTION, 0f)
        assertEquals(0.9f, SMOOTH_SHEET_SPRING_DAMPING_RATIO, 0f)
        assertEquals(350f, SMOOTH_SHEET_SPRING_STIFFNESS, 0f)
    }

    @Test
    fun `snap target preserves position and direction quadrants`() {
        assertEquals(
            SmoothBottomSheetValue.Partial,
            smoothBottomSheetSnapTarget(position = 0.3f, velocityDirection = 1f),
        )
        assertEquals(
            SmoothBottomSheetValue.Hidden,
            smoothBottomSheetSnapTarget(position = 0.3f, velocityDirection = -1f),
        )
        assertEquals(
            SmoothBottomSheetValue.Expanded,
            smoothBottomSheetSnapTarget(position = 0.7f, velocityDirection = 1f),
        )
        assertEquals(
            SmoothBottomSheetValue.Partial,
            smoothBottomSheetSnapTarget(position = 0.7f, velocityDirection = -1f),
        )
    }

    @Test
    fun `caller dismissal requests are observable by the shared shell`() {
        val state = SmoothBottomSheetState()

        assertEquals(0, state.dismissRequestVersion)
        state.requestDismiss()
        assertEquals(1, state.dismissRequestVersion)
    }

    @Test
    fun `every sheet value owns one established anchor`() {
        assertEquals(0f, SmoothBottomSheetValue.Hidden.fraction, 0f)
        assertEquals(0.45f, SmoothBottomSheetValue.Partial.fraction, 0f)
        assertEquals(0.94f, SmoothBottomSheetValue.Expanded.fraction, 0f)
    }
}
