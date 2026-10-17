package com.newoether.agora.ui.chat.message

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.newoether.agora.ui.components.latexImageRequest
import com.newoether.agora.ui.components.latexSourceForLink
import com.newoether.agora.ui.components.parseLatexSpans
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Copying rendered text reproduces the original message text, formulas included. */
class LatexSourceCopyTest {
    private val settings = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(),
        codeSpanStyle = SpanStyle(),
        annotator = chatMarkdownAnnotator,
    )

    @Test
    fun formulaSpansKeepTheirExactSourceSlice() {
        val text = "a \\(x\\) b \$y\$ c\n\n\$\$ z \$\$\n\n\\[w\\]"
        val sources = parseLatexSpans(text, parseInlineDollarMath = true)
            .filter { it.isLatex }
            .map { it.source }
        // Inline formulas also carry the whitespace trimmed from their neighbours.
        assertEquals(listOf(" \\(x\\) ", " \$y\$ ", "\$\$ z \$\$", "\\[w\\]"), sources)
    }

    @Test
    fun inlineFormulaAlternateTextIsItsSourceSoCopyEqualsTheOriginal() {
        val original = "So \\(a+b\\) and \$c^2\$ end."
        val markdown = original.toRenderableMarkdownText(parseInlineDollarMath = true)
        val paragraph = paragraphs(markdown).single()
        val text = markdown.buildMarkdownAnnotatedString(paragraph, TextStyle(), settings).text

        assertEquals(original, text)
        assertFalse(text.contains("latex://"))
    }

    @Test
    fun inlineFormulaAlternateTextResolvesToTheSameFormulaAsItsUrl() {
        // The markdown renderer passes an inline image's alternate text, not its URL, to the
        // image transformer, so a formula whose alternate text is its source must still resolve.
        val original = "So \\(a+b\\) and \$c^2\$ end, \\(\\frac{1}{2}\\)."
        val markdown = original.toRenderableMarkdownText(parseInlineDollarMath = true)
        val annotated = markdown.buildMarkdownAnnotatedString(paragraphs(markdown).single(), TextStyle(), settings)
        val images = annotated.getStringAnnotations(0, annotated.length)
            .filter { it.item.startsWith("${MARKDOWN_TAG_IMAGE_URL}_") }
        assertEquals(3, images.size)
        images.forEach { image ->
            val fromUrl = latexImageRequest(image.item.removePrefix("${MARKDOWN_TAG_IMAGE_URL}_"))
            assertNotNull(fromUrl)
            assertEquals(fromUrl, latexImageRequest(annotated.text.substring(image.start, image.end)))
        }
    }
    @Test
    fun displayOnlyParagraphsResolveToTheirFormulaSource() {
        val original = "Before\n\n\$\$x^2\$\$\n\n\\[\\frac{a}{b}\\]\n\nAfter \\(y\\)"
        val markdown = original.toRenderableMarkdownText(parseInlineDollarMath = true)
        val sources = paragraphs(markdown).map { paragraph ->
            displayLatexParagraphLink(markdown, paragraph)?.let(::latexSourceForLink)
        }

        assertEquals(listOf(null, "\$\$x^2\$\$", "\\[\\frac{a}{b}\\]", null), sources)
    }

    @Test
    fun nonFormulaLinksHaveNoSource() {
        assertNull(latexSourceForLink("https://example.com/formula.png"))
        assertNull(latexSourceForLink("latex://display/%"))
    }

    private fun paragraphs(markdown: String): List<ASTNode> {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val found = mutableListOf<ASTNode>()
        fun visit(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH) found += node else node.children.forEach(::visit)
        }
        visit(root)
        return found
    }
}
