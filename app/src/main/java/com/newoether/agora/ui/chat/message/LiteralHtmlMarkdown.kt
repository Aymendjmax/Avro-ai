package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.newoether.agora.ui.components.formulaAlternateText
import com.newoether.agora.ui.components.latexSourceForLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * The Compose renderer does not provide default output for inline HTML AST nodes. Treat model
 * output as literal Markdown text instead of executable HTML and append the original source range.
 */
private fun AnnotatedString.Builder.appendLiteralHtml(content: String, child: ASTNode): Boolean =
    if (child.type == MarkdownTokenTypes.HTML_TAG) {
        append(child.getTextInNode(content))
        true
    } else {
        false
    }

/** Literal inline HTML only, for Markdown outside chat messages. */
internal val literalHtmlMarkdownAnnotator: MarkdownAnnotator = markdownAnnotator { content, child ->
    appendLiteralHtml(content, child)
}

/**
 * Chat Markdown rules on top of the renderer defaults.
 *
 * - Inline HTML stays literal text.
 * - A LaTeX image keeps the renderer's inline-content tag, so placeholders and block promotion are
 *   unchanged, but its alternate text is the formula's original source instead of its `latex://`
 *   URL. Selection copies that alternate text.
 */
internal val chatMarkdownAnnotator: MarkdownAnnotator = markdownAnnotator { content, child ->
    if (appendLiteralHtml(content, child)) return@markdownAnnotator true
    if (child.type != MarkdownElementTypes.IMAGE) return@markdownAnnotator false
    val link = markdownImageLink(content, child, null)
    val source = link?.let(::latexSourceForLink) ?: return@markdownAnnotator false
    appendInlineContent("${MARKDOWN_TAG_IMAGE_URL}_$link", formulaAlternateText(source))
    true
}
internal fun literalHtmlBlockText(content: String, node: ASTNode): String? =
    if (node.type == MarkdownElementTypes.HTML_BLOCK) {
        node.getTextInNode(content).toString()
    } else {
        null
    }

/**
 * Block HTML bypasses the annotated-string path entirely, so render its exact source range as
 * selectable plain text. The original Markdown source remains available for selection semantics.
 */
@Composable
internal fun LiteralHtmlMarkdownBlock(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier,
) {
    val text = remember(model.content, model.node) {
        AnnotatedString(requireNotNull(literalHtmlBlockText(model.content, model.node)))
    }
    MarkdownText(
        content = text,
        node = model.node,
        modifier = modifier,
        style = model.typography.paragraph,
        sourceContent = model.content,
    )
}
