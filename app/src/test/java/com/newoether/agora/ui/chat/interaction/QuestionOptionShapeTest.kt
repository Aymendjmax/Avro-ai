package com.newoether.agora.ui.chat.interaction

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionOptionShapeTest {
    private fun radiusFor(height: Float): Float {
        val outline = OptionShape.createOutline(Size(300f, height), LayoutDirection.Ltr, Density(2f))
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }

    @Test
    fun singleLineOptionIsACapsule() = assertEquals(20f, radiusFor(40f))

    @Test
    fun wrappedOptionStopsAt24Dp() = assertEquals(48f, radiusFor(200f))
}
