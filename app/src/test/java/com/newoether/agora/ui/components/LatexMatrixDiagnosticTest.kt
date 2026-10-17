package com.newoether.agora.ui.components

import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.ui.chat.message.toRenderableMarkdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Diagnostic only, pending the owner's decision on the matrix defect.
 *
 * Prints what a matrix becomes on the way from the message text to the renderer, so the stage that
 * loses it can be named instead of guessed at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatexMatrixDiagnosticTest {
    @Before
    fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    private val matrix = "\\begin{pmatrix}\na & b \\\\\nc & d\n\\end{pmatrix}"

    private val messages = linkedMapOf(
        "own paragraph" to "Before\n\n$$\n$matrix\n$$\n\nAfter",
        "alone" to "$$\n$matrix\n$$",
        "one line" to "\$\$$matrix\$\$",
        "bmatrix" to "$$\n\\begin{bmatrix}1 & 0 \\\\ 0 & 1\\end{bmatrix}\n$$",
        "cr rows" to "$$\n\\begin{pmatrix}1 & 2\\cr 3 & 4\\end{pmatrix}\n$$",
        "inline dollar" to "value \$\\begin{pmatrix}1 & 2 \\\\ 3 & 4\\end{pmatrix}\$ here",
        "bare environment" to matrix,
        "no matrix baseline" to "Before\n\n\$\$\nD=\\sqrt{a^2+b^2+c^2}\n\$\$\n\nAfter",
    )

    @Test
    fun reportTheWholeTrip() {
        for ((label, text) in messages) {
            val spans = parseLatexSpans(text, parseInlineDollarMath = true)
            val markdown = text.toRenderableMarkdownText(parseInlineDollarMath = true)
            val recovered = markdown.lines()
                .filter { it.startsWith("![latex](") }
                .mapNotNull { line ->
                    val link = line.substringAfter("(").substringBefore(")")
                    latexImageRequest(link)?.let { "${it.display} ${it.latex.replace("\n", "\\n")}" }
                }
            println(
                "LATEXDIAG | $label | latexSpans=${spans.count { it.isLatex }} | " +
                    "markdown=${markdown.replace("\n", "\\n")}",
            )
            println("LATEXDIAG   recovered=${recovered.joinToString(" ; ").ifEmpty { "NONE" }}")
        }
    }

    private val reportedMatrix = """\begin{pmatrix} 2 & -1 & 4 \\ 0 & 3 & 7 \\ 5 & 6 & -2 \end{pmatrix}"""

    @Test
    fun environmentMacroReflectionEntryRemainsCallable() {
        org.scilab.forge.jlatexmath.TeXFormula(reportedMatrix)
        val handler = org.scilab.forge.jlatexmath.MacroInfo(
            "org.scilab.forge.jlatexmath.NewCommandMacro", "executeMacro", 1f,
        )
        val parser = org.scilab.forge.jlatexmath.TeXParser(
            "", org.scilab.forge.jlatexmath.TeXFormula(), false,
        )
        val arguments = arrayOfNulls<String>(12).apply {
            this[0] = "pmatrix@env"
            this[1] = "2 & -1 & 4"
        }
        val expansion = handler.invoke(parser, arguments) as String
        assertTrue("environment expands through the reflected handler", expansion.contains("\\begin{matrix}"))
        assertTrue("environment expansion retains its argument", expansion.contains("2 & -1 & 4"))
    }

    @Test
    fun reportedMatrixBuildsInTheCurrentJlatexmathDependency() {
        val source = "\$$reportedMatrix\$"
        val formula = parseLatexSpans(source, parseInlineDollarMath = true).single { it.isLatex }
        assertEquals(reportedMatrix, formula.content)
        assertEquals(false, formula.display)
        val drawable = ru.noties.jlatexmath.JLatexMathDrawable.builder(formula.content)
            .textSize(60f)
            .color(0xFF000000.toInt())
            .build()
        assertTrue("matrix drawable width", drawable.intrinsicWidth > 0)
        assertTrue("matrix drawable height", drawable.intrinsicHeight > 0)
        println("LATEXDIAG matrix drawable=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
    }

    @Test
    fun reportedMatrixDrawsInkThroughTheProductionBitmapRenderer() {
        val bitmap = requireNotNull(renderLatexToBitmap(reportedMatrix, textSize = 60f)) {
            "Matrix bitmap failed; inspect LatexRenderer exception in the test log"
        }
        try {
            var ink = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if ((bitmap.getPixel(x, y) ushr 24) > 128) ink++
                }
            }
            assertTrue("matrix bitmap must contain visible ink", ink > 0)
            println("LATEXDIAG matrix bitmap=${bitmap.width}x${bitmap.height}, ink=$ink")
        } finally {
            bitmap.recycle()
        }
    }

    /** Parsing keeps the full matrix before the renderer is invoked. */
    @Test
    fun spanParsingKeepsTheMatrixWhole() {
        val spans = parseLatexSpans("$$\n$matrix\n$$", parseInlineDollarMath = false)
        val formula = spans.single { it.isLatex }
        assertEquals(matrix, formula.content)
        assertEquals(true, formula.display)
    }
}
