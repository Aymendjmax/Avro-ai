package com.newoether.agora.ui.chat

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.chat.message.scaledMarkdownTextStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFileViewerTypographyTest {
    @Test
    fun `shared markdown scaler preserves app typography with centered one point one line height`() {
        val appStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )

        val scaled = scaledMarkdownTextStyle(appStyle)

        assertEquals(FontFamily.SansSerif, scaled.fontFamily)
        assertEquals(FontWeight.Medium, scaled.fontWeight)
        assertEquals(13.sp, scaled.fontSize)
        assertEquals(22.sp, scaled.lineHeight)
        assertEquals(LineHeightStyle.Alignment.Center, scaled.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.Both, scaled.lineHeightStyle?.trim)
    }

    @Test
    fun `full screen text viewer uses app font bold headings and scaled markdown tiers`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/TextFileViewer.kt",
        )

        assertFalse(source.contains("MonoFamily"))
        assertFalse(source.contains("FontFamily.Monospace"))

        assertTrue(source.contains("val viewerBodyStyle = scaledMarkdownTextStyle(t.bodyLarge)"))
        listOf("text", "paragraph", "ordered", "bullet", "list", "table").forEach { tier ->
            assertTrue(source.contains("$tier = viewerBodyStyle"))
        }

        val headings = listOf(
            "h1 = scaledMarkdownTextStyle(t.headlineMedium.copy(fontWeight = FontWeight.Bold))",
            "h2 = scaledMarkdownTextStyle(t.headlineSmall.copy(fontWeight = FontWeight.Bold))",
            "h3 = scaledMarkdownTextStyle(t.titleLarge.copy(fontWeight = FontWeight.Bold))",
            "h4 = scaledMarkdownTextStyle(t.titleMedium.copy(fontWeight = FontWeight.Bold))",
            "h5 = scaledMarkdownTextStyle(t.titleSmall.copy(fontWeight = FontWeight.Bold))",
            "h6 = scaledMarkdownTextStyle(t.titleSmall.copy(fontWeight = FontWeight.Bold))",
        )
        headings.forEach { assertTrue(source.contains(it)) }

        assertEquals(
            2,
            Regex(
                """(?:code|inlineCode) = scaledMarkdownTextStyle\(t\.bodyMedium\.copy\(fontSize = 13\.sp\)\)""",
            ).findAll(source).count(),
        )

        val plainTextBranch = source
            .substringAfter("} else {")
            .substringBefore("// Top alpha gradient")
        assertTrue(plainTextBranch.contains("style = t.bodyMedium.copy("))
        assertTrue(plainTextBranch.contains("fontSize = 13.sp"))
        assertTrue(plainTextBranch.contains("lineHeight = 20.sp"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
