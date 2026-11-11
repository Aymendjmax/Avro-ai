package com.newoether.agora.data

import com.newoether.agora.data.SkillManager.SkillFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionParserTest {

    @Test
    fun testDetectActiveSkillQueryAtStart() {
        val text = "/py"
        val query = MentionParser.detectActiveQuery(text, selectionEnd = 3)
        assertNotNull(query)
        assertEquals(MentionParser.MentionType.SKILL, query?.type)
        assertEquals("py", query?.query)
        assertEquals(0, query?.startIndex)
    }

    @Test
    fun testDetectActiveMcpQueryAfterSpace() {
        val text = "Please call @git"
        val query = MentionParser.detectActiveQuery(text, selectionEnd = 16)
        assertNotNull(query)
        assertEquals(MentionParser.MentionType.MCP, query?.type)
        assertEquals("git", query?.query)
        assertEquals(12, query?.startIndex)
    }

    @Test
    fun testDoNotDetectInsideWord() {
        val text = "http://example.com/test"
        val query = MentionParser.detectActiveQuery(text, selectionEnd = text.length)
        // '/' is preceded by 'm', not whitespace or start of string
        assertNull(query)

        val email = "user@domain.com"
        val queryEmail = MentionParser.detectActiveQuery(email, selectionEnd = email.length)
        assertNull(queryEmail)
    }

    @Test
    fun testExtractMentions() {
        val message = "Review this using /code-review and fetch files from @github-server and /python."
        val skills = MentionParser.extractSkillMentions(message)
        val mcps = MentionParser.extractMcpMentions(message)

        assertTrue(skills.contains("code-review"))
        assertTrue(skills.contains("python"))
        assertTrue(mcps.contains("github-server"))
    }

    @Test
    fun testResolveMentions() {
        val message = "Run /python and check @my-mcp"
        val availableSkills = listOf(
            SkillFileInfo("python.md", "Python execution skill"),
            SkillFileInfo("bash.md", "Bash shell skill"),
        )
        val availableMcp = listOf(
            McpServerConfig(id = "srv-1", name = "my-mcp"),
            McpServerConfig(id = "srv-2", name = "other-mcp"),
        )

        val result = MentionParser.resolve(message, availableSkills, availableMcp)
        assertEquals(1, result.validSkills.size)
        assertEquals("python.md", result.validSkills.first().name)
        assertEquals(1, result.validMcpServers.size)
        assertEquals("my-mcp", result.validMcpServers.first().name)
        assertTrue(result.augmentedText.contains("[Active Skills: python.md]"))
    }

    @Test
    fun testDetectArabicSkillQuery() {
        val text = "ساعدني عبر /تحليل"
        val query = MentionParser.detectActiveQuery(text, selectionEnd = text.length)
        assertNotNull(query)
        assertEquals(MentionParser.MentionType.SKILL, query?.type)
        assertEquals("تحليل", query?.query)
    }

    @Test
    fun testDetectArabicMcpQuery() {
        val text = "استدعاء @خادم_الملفات"
        val query = MentionParser.detectActiveQuery(text, selectionEnd = text.length)
        assertNotNull(query)
        assertEquals(MentionParser.MentionType.MCP, query?.type)
        assertEquals("خادم_الملفات", query?.query)
    }
}
