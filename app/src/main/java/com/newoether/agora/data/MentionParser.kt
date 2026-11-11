package com.newoether.agora.data

import com.newoether.agora.data.SkillManager.SkillFileInfo

object MentionParser {
    // Regex for skills: starts with / followed by alphanumeric/Unicode letters, hyphen, underscore, dot (e.g. /python, /my-skill.md, /مهارة)
    private val SKILL_MENTION_REGEX = Regex("""(?<=\s|^)/([\p{L}\p{N}_\-\.]+)""")
    // Regex for MCP: starts with @ followed by alphanumeric/Unicode letters, hyphen, underscore, dot (e.g. @github, @fs, @خادم)
    private val MCP_MENTION_REGEX = Regex("""(?<=\s|^)@([\p{L}\p{N}_\-\.]+)""")

    enum class MentionType {
        SKILL,
        MCP,
    }

    data class ActiveMentionQuery(
        val type: MentionType,
        val query: String,
        val startIndex: Int,
        val endIndex: Int,
    )

    data class ResolvedMentions(
        val validSkills: List<SkillFileInfo>,
        val validMcpServers: List<McpServerConfig>,
        val augmentedText: String,
    )

    fun detectActiveQuery(text: String, selectionEnd: Int): ActiveMentionQuery? {
        if (selectionEnd <= 0 || selectionEnd > text.length) return null
        val beforeCursor = text.substring(0, selectionEnd)
        val lastSlash = beforeCursor.lastIndexOf('/')
        val lastAt = beforeCursor.lastIndexOf('@')

        val (triggerIndex, type) = when {
            lastSlash > lastAt -> lastSlash to MentionType.SKILL
            lastAt > lastSlash -> lastAt to MentionType.MCP
            else -> return null
        }

        // Verify that the trigger is preceded by start of string or whitespace
        if (triggerIndex > 0 && !text[triggerIndex - 1].isWhitespace()) {
            return null
        }

        val query = beforeCursor.substring(triggerIndex + 1)
        // If there is any whitespace in the query, the mention token was completed or ended
        if (query.any { it.isWhitespace() }) return null

        return ActiveMentionQuery(
            type = type,
            query = query,
            startIndex = triggerIndex,
            endIndex = selectionEnd,
        )
    }

    fun extractSkillMentions(text: String): Set<String> {
        return SKILL_MENTION_REGEX.findAll(text).map { it.groupValues[1].lowercase() }.toSet()
    }

    fun extractMcpMentions(text: String): Set<String> {
        return MCP_MENTION_REGEX.findAll(text).map { it.groupValues[1].lowercase() }.toSet()
    }

    fun resolve(
        rawText: String,
        availableSkills: List<SkillFileInfo>,
        availableMcpServers: List<McpServerConfig>,
    ): ResolvedMentions {
        val rawSkillTokens = extractSkillMentions(rawText)
        val rawMcpTokens = extractMcpMentions(rawText)

        val matchedSkills = availableSkills.filter { skill ->
            val baseName = skill.name.removeSuffix(".md").lowercase()
            val fullName = skill.name.lowercase()
            rawSkillTokens.contains(baseName) || rawSkillTokens.contains(fullName)
        }

        val matchedMcp = availableMcpServers.filter { server ->
            val name = server.name.lowercase()
            val id = server.id.lowercase()
            rawMcpTokens.contains(name) || rawMcpTokens.contains(id)
        }

        val augmentedText = if (matchedSkills.isNotEmpty()) {
            val skillNames = matchedSkills.joinToString(", ") { it.name }
            "$rawText\n\n[Active Skills: $skillNames]"
        } else {
            rawText
        }

        return ResolvedMentions(
            validSkills = matchedSkills,
            validMcpServers = matchedMcp,
            augmentedText = augmentedText,
        )
    }
}
