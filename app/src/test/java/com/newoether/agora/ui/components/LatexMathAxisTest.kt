package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendered formulas put the TeX math axis on the bitmap's vertical center. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatexMathAxisTest {
    @Before
    fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun fractionBarSitsOnTheVerticalCenter() {
        // A fraction bar is drawn on the math axis, so it marks where the axis ended up.
        listOf("\\frac{a}{b}", "\\frac{x^2}{y}", "\\frac{1}{\\sqrt{2}}").forEach { latex ->
            val bitmap = requireNotNull(renderLatexToBitmap(latex, textSize = 60f, color = 0xFF000000.toInt()))
            val bar = widestInkRow(bitmap)
            val center = bitmap.height / 2f
            assertTrue("$latex: bar at $bar, center $center, height ${bitmap.height}", kotlin.math.abs(bar - center) <= 2f)
        }
    }

    @Test
    fun paddingGoesOnTheShortSide() {
        assertEquals(AxisPadding(top = 4, bottom = 0), axisCenteringPadding(axisY = 8f, height = 20))
        assertEquals(AxisPadding(top = 0, bottom = 6), axisCenteringPadding(axisY = 13f, height = 20))
        assertEquals(AxisPadding(top = 0, bottom = 0), axisCenteringPadding(axisY = 10f, height = 20))
    }

    private fun widestInkRow(bitmap: Bitmap): Float {
        val rows = (0 until bitmap.height).map { y ->
            (0 until bitmap.width).count { x -> (bitmap.getPixel(x, y) ushr 24) > 128 }
        }
        val widest = rows.max()
        // A bar can be more than one pixel thick; use the middle of its rows.
        val barRows = rows.indices.filter { rows[it] >= widest - 1 }
        return (barRows.first() + barRows.last()) / 2f + 0.5f
    }
}
