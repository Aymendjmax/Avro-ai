package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatingModeContextBuilderTest {

    @Test
    fun testWorkModeContextContainsEnvironmentAndUploads() {
        val basePrompt = "You are a helpful assistant."
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = basePrompt,
            mode = ConversationMode.WORK,
            conversationId = "chat_abc_123",
            shellAvailable = true,
        )

        assertTrue(result != null)
        assertTrue(result!!.startsWith(basePrompt))
        assertTrue(result.contains("Mode: WORK (Autonomous Agent & Developer Environment)"))
        assertTrue(result.contains("/home/uploads/chat_abc_123"))
        assertTrue(result.contains("/home/outputs/chat_abc_123"))
        assertTrue(result.contains("PRoot Alpine Linux 3.21 container"))
        assertTrue(result.contains("musl and busybox"))
        assertTrue(result.contains("apk add --no-cache"))
        assertTrue(result.contains("execute_shell_command"))
        assertTrue(result.contains("Autonomous Execution Directives (MANDATORY)"))
    }

    @Test
    fun testAskModeContextContainsModeConstraintsAndWorkTransition() {
        val basePrompt = "You are an AI assistant."
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = basePrompt,
            mode = ConversationMode.ASK,
            conversationId = "chat_456",
        )

        assertTrue(result != null)
        assertTrue(result!!.startsWith(basePrompt))
        assertTrue(result.contains("Mode: ASK (Conversational Mode)"))
        assertTrue(result.contains("Direct shell execution, terminal commands, and local filesystem manipulation are disabled"))
        assertTrue(result.contains("Avro Work Mode & Transitions"))
        assertTrue(result.contains("request_work"))
        assertFalse(result.contains("Mode: WORK (Autonomous Agent & Developer Environment)"))
    }

    @Test
    fun testNullBasePromptWithWorkMode() {
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = null,
            mode = ConversationMode.WORK,
            conversationId = "conversation_xyz",
        )

        assertTrue(result != null)
        assertTrue(result!!.contains("Mode: WORK"))
        assertTrue(result.contains("/home/uploads/conversation_xyz"))
    }

    @Test
    fun testBlankBasePromptWithAskMode() {
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = "   ",
            mode = ConversationMode.ASK,
            conversationId = "conversation_xyz",
        )

        assertTrue(result != null)
        assertTrue(result!!.contains("Mode: ASK"))
        assertTrue(result.contains("request_work"))
    }

    @Test
    fun testWorkModeContextWhenShellUnavailableExplicitlyStatesToolsAreUnavailable() {
        val basePrompt = "You are a helpful assistant."
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = basePrompt,
            mode = ConversationMode.WORK,
            conversationId = "chat_abc_123",
            shellAvailable = false,
        )

        assertTrue(result != null)
        assertTrue(result!!.contains("Mode: WORK (Autonomous Agent & Developer Environment)"))
        assertTrue(result.contains("Shell Tools & Local Sandbox: The local Alpine Linux sandbox is not installed yet on this device"))
        assertTrue(result.contains("Alpine Linux sandbox needs first-time installation"))
        assertFalse(result.contains("PRoot Alpine Linux 3.21 container"))
    }

    @Test
    fun testCompactWorkModeInstructions() {
        val result = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = "Base prompt",
            mode = ConversationMode.WORK,
            conversationId = "chat_compact",
            shellAvailable = true,
            isCompact = true,
        )

        assertTrue(result != null)
        assertTrue(result!!.contains("Mode: WORK (Compact Autonomous Environment)"))
        assertTrue(result.contains("PRoot Alpine Linux 3.21"))
        assertTrue(result.contains("Autonomous Directives:"))
        // Ensure compact instructions are shorter and concise
        assertTrue(result.length < 600)
    }

    @Test
    fun testStandaloneEnvironmentAndDirectivesBuilders() {
        val env = OperatingModeContextBuilder.buildEnvironmentInstructions("chat_env", shellAvailable = true, isCompact = false)
        val rules = OperatingModeContextBuilder.buildDirectivesInstructions("chat_env", isCompact = false)

        assertTrue(env.contains("/home/uploads/chat_env"))
        assertTrue(env.contains("/home/outputs/chat_env"))
        assertTrue(rules.contains("Autonomous Execution Directives (MANDATORY)"))
    }

    @Test
    fun testDeduplicationWhenExplicitVariablesPresent() {
        val baseWithBoth = "Custom prompt with {work_environment} and {work_rules} already resolved."
        val resultBoth = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = baseWithBoth,
            mode = ConversationMode.WORK,
            conversationId = "chat_dedup",
            shellAvailable = true,
            hasExplicitEnvironmentVar = true,
            hasExplicitRulesVar = true,
        )

        // When both explicit variables are present in the user's template, no extra <operating_mode> section is appended
        org.junit.Assert.assertEquals(baseWithBoth, resultBoth)

        // When only environment is present, only directives are appended
        val resultOnlyEnv = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = "Only env in template",
            mode = ConversationMode.WORK,
            conversationId = "chat_dedup",
            shellAvailable = true,
            hasExplicitEnvironmentVar = true,
            hasExplicitRulesVar = false,
        )
        assertTrue(resultOnlyEnv!!.contains("Autonomous Execution Directives (MANDATORY)"))
        assertFalse(resultOnlyEnv.contains("PRoot Alpine Linux 3.21 container"))

        // When only rules is present, only environment is appended
        val resultOnlyRules = OperatingModeContextBuilder.buildModeSystemContext(
            basePrompt = "Only rules in template",
            mode = ConversationMode.WORK,
            conversationId = "chat_dedup",
            shellAvailable = true,
            hasExplicitEnvironmentVar = false,
            hasExplicitRulesVar = true,
        )
        assertTrue(resultOnlyRules!!.contains("PRoot Alpine Linux 3.21 container"))
        assertFalse(resultOnlyRules.contains("Autonomous Execution Directives (MANDATORY)"))
    }
}
