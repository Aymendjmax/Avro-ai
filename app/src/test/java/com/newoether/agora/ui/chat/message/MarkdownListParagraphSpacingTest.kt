package com.newoether.agora.ui.chat.message

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownListParagraphSpacingTest {
    @Test
    fun `blank lines inside a list item create spaced sibling paragraphs`() {
        val source = """
            1. **三个开关是假象**

               它们不是三个独立开关，而是同一个采集等级的三种状态。

               更严重的是，每次切换等级都会调用 `start()`。
        """.trimIndent()

        val paragraphs = parse(source).firstListItemParagraphs()

        assertEquals(3, paragraphs.size)
        assertFalse(paragraphs[0].needsListParagraphSpacer())
        assertTrue(paragraphs[1].needsListParagraphSpacer())
        assertTrue(paragraphs[2].needsListParagraphSpacer())
    }

    @Test
    fun `line breaks within the first list paragraph do not add a block spacer`() {
        val source = """
            1. **Tool Call 刚开始时**  
               UI 会先更新工具状态，并等待持久化检查点完成。
               这一行仍属于同一个段落。
        """.trimIndent()

        val paragraphs = parse(source).firstListItemParagraphs()

        assertEquals(1, paragraphs.size)
        assertFalse(paragraphs.single().needsListParagraphSpacer())
    }

    @Test
    fun `paragraphs outside list items never receive the list spacer`() {
        val paragraphs = parse("First paragraph.\n\nSecond paragraph.")
            .children
            .filter { it.type == MarkdownElementTypes.PARAGRAPH }

        assertEquals(2, paragraphs.size)
        paragraphs.forEach { assertFalse(it.needsListParagraphSpacer()) }
    }

    private fun parse(source: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    private fun ASTNode.firstListItemParagraphs(): List<ASTNode> {
        val listItem = descendants().first { it.type == MarkdownElementTypes.LIST_ITEM }
        return listItem.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
    }

    private fun ASTNode.descendants(): Sequence<ASTNode> = sequence {
        yield(this@descendants)
        children.forEach { yieldAll(it.descendants()) }
    }
}
