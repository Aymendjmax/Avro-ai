package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Canvas for rendering JLatexMath formulas.
 *
 * jlatexmath-android's AndroidGraphics2D shares a single Paint: draw() switches it to STROKE,
 * fill() to FILL, but drawChars() never resets the style. Any glyph drawn after a stroked shape
 * (for example the frame drawn by \colorbox or \fbox) is therefore rendered as a hollow outline.
 * Forcing FILL for text restores solid glyphs without touching how rules and frames are drawn.
 */
internal class FilledTextCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
        val style = paint.style
        paint.style = Paint.Style.FILL
        super.drawText(text, index, count, x, y, paint)
        paint.style = style
    }
}
