package com.newoether.agora.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomSheetShapeTest {
    @Test
    fun everySheetUsesTheSharedTwentyEightDpTopCorners() {
        val size = Size(400f, 600f)
        val density = Density(1f)
        assertEquals(28f, BOTTOM_SHEET_SHAPE.topStart.toPx(size, density), 0.001f)
        assertEquals(28f, BOTTOM_SHEET_SHAPE.topEnd.toPx(size, density), 0.001f)
        assertEquals(0f, BOTTOM_SHEET_SHAPE.bottomStart.toPx(size, density), 0.001f)
        val ui = File("src/main/java/com/newoether/agora/ui")
        assertTrue(File(ui, "motion/MotionAwareModalBottomSheet.kt").readText().contains("shape = BOTTOM_SHEET_SHAPE"))
        assertTrue(File(ui, "components/SmoothBottomSheet.kt").readText().contains("shape = BOTTOM_SHEET_SHAPE"))
    }
}