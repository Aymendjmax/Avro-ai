package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.ToolExecutionStates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationResolverTest {

    @Test
    fun providerHostedSearchNamesUseWebSearchPresentation() {
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindForToolName("openai_search"))
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindForToolName("google_search"))
    }

    @Test
    fun googleSearchGroundingUsesNormalizedResults() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "google_search",
                toolArgs = """{"query":"Agora"}""",
                toolResult = """{"type":"web_search","provider":"Google","query":"Agora","results":[{"title":"Agora","url":"https://example.com"}]}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(ToolKind.WEB_SEARCH, presentation.kind)
        assertEquals("Agora", presentation.subject)
        assertEquals(1, presentation.count)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }

    @Test
    fun runningCodeExecutionRemainsAnActiveHostedTool() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "code_execution",
                toolArgs = """{"language":"PYTHON","code":"print(1)"}""",
                toolState = ToolExecutionStates.RUNNING,
            ),
        )

        assertEquals(ToolPresentationState.RUNNING, presentation.state)
        assertTrue(presentation.isActive)
    }

    @Test
    fun shellCommandSummary_isAlwaysSingleLineAndBounded() {
        val command = "  echo first\r\n   &&   echo second  "

        assertEquals(
            "echo first && echo second",
            singleLineShellCommand(command),
        )
        assertEquals(null, singleLineShellCommand(" \n\t "))
        assertEquals("1234\u2026", singleLineShellCommand("123456789", maxCharacters = 5))
    }

    @Test
    fun unfinishedArgumentsExposeProgressiveSubjectsAcrossToolKinds() {
        val cases = listOf(
            Triple("execute_shell_command", """{"command":"cp /tmp/sour""", "cp /tmp/sour"),
            Triple("read_memory_file", """{"name":"project-no""", "project-no"),
            Triple("web_search", """{"query":"compose stream""", "compose stream"),
            Triple("web_fetch", """{"url":"https://exam""", "https://exam"),
            Triple("search_conversations", """{"query":"old deci""", "old deci"),
            Triple("read_conversation", """{"conversation_id":"conv-12""", "conv-12"),
            Triple("get_shell_job", """{"job_id":"job-45""", "job-45"),
            Triple("file_write", """{"path":"/tmp/progr""", "/tmp/progr"),
            Triple("view_image", """{"path":"/tmp/previ""", "/tmp/previ"),
            Triple("file_glob", """{"pattern":"**/*.k""", "**/*.k"),
            Triple("generate_image", """{"prompt":"blue mount""", "blue mount"),
            Triple("create_task", """{"name":"nightly ba""", "nightly ba"),
            Triple("delete_task", """{"id_or_name":"nightly ba""", "nightly ba"),
        )

        cases.forEach { (toolName, partialArguments, expectedSubject) ->
            val presentation = ToolPresentationResolver.resolve(
                MessageSegment(
                    type = "tool",
                    toolName = toolName,
                    toolArgs = partialArguments,
                    toolState = ToolExecutionStates.CALLING,
                ),
            )

            assertEquals(toolName, expectedSubject, presentation.subject)
            assertEquals(ToolPresentationState.CALLING, presentation.state)
        }
    }

    @Test
    fun unfinishedCommandDecodesEscapesAndKeepsGrowing() {
        val first = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf \"hel""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )
        val second = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf \"hello\"""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )

        assertEquals("printf \"hel", first.subject)
        assertEquals("printf \"hello\"", second.subject)
    }

    @Test
    fun missingArgumentsDoNotInventAToolNameAsSubject() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )

        assertEquals(null, presentation.subject)
    }

    @Test
    fun emptyGlobJsonIsZeroFilesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_glob",
                toolArgs = """{"pattern":"*.kt"}""",
                toolResult = """{"type":"file_glob","files":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun emptyGrepJsonIsZeroMatchesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_grep",
                toolArgs = """{"pattern":"missing"}""",
                toolResult = """{"type":"file_grep","matches":[]}""",
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun nullResultAndLiveOutputAreRunning() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = null,
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "line one\n",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(ToolPresentationState.RUNNING, presentation.state)
        assertEquals("line one\n", presentation.liveOutput)
        assertEquals("tinybox", presentation.device)
    }

    @Test
    fun backgroundJobIsNotActiveAfterToolCallReturns() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"background":true,"job_id":"abc","state":"running"}""",
            ),
        )

        assertEquals(ToolPresentationState.BACKGROUND_RUNNING, presentation.state)
        assertEquals("abc", presentation.jobId)
        // A detached background job must not occupy the group loading indicator.
        assertFalse(presentation.isActive)
    }

    @Test
    fun structuredErrorUsesServerMessage() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"error":"error","message":"Cannot connect to Conch: refused"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("Cannot connect to Conch: refused", presentation.errorMessage)
    }

    @Test
    fun providerNoResultsCodeIsAnEmptySuccess() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolArgs = """{"query":"nothing"}""",
                toolResult = """{"type":"web_search","query":"nothing","error":"no_results"}""",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.EMPTY, presentation.state)
        assertEquals(null, presentation.errorMessage)
    }

    @Test
    fun structuredErrorWithoutMessageStillFailsWithSpecificCode() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolResult = """{"type":"web_search","error":"no_api_key"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("no api key", presentation.errorMessage)
    }

    @Test
    fun shellOutputLengthExcludesJsonEnvelope() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":"abc"}""",
            ),
        )

        assertEquals(3, presentation.outputLength)
    }

    @Test
    fun successfulShellWithoutOutputIsStillSucceeded() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":""}""",
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(0, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitIsCompleted() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":127,"output":"not found"}""",
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(127, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitKeepsSucceededWireState() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.SUCCEEDED,
                toolResult = """{"type":"execute_shell_command","exit_code":2,"output":"bad arguments"}""",
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(2, presentation.exitCode)
    }

    @Test
    fun completedShellResultUsesAuthoritativeServerAndOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"server":"requested"}""",
                toolResult = """
                    {"type":"execute_shell_command","server":"actual","exit_code":0,"output":"done"}
                """.trimIndent(),
                toolTarget = "resolved",
            ),
        )

        assertEquals("actual", presentation.device)
        assertEquals("done", shellOutputText(presentation))
    }

    @Test
    fun durableForegroundShellUnwrapsTerminalExitAndOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf done"}""",
                toolResult = """
                    {
                      "type":"execute_shell_command",
                      "server":"conch",
                      "job_id":"job-1",
                      "result":{"state":"succeeded","exit_code":0,"output":"done"}
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(ShellPresentationStatus.Exit(0), shellPresentationStatus(presentation))
        assertEquals(0, presentation.exitCode)
        assertEquals("done", shellOutputText(presentation))
        assertEquals("conch", presentation.device)
        assertEquals("job-1", presentation.jobId)
    }

    @Test
    fun shellPresentationDistinguishesForegroundBackgroundAndExitStates() {
        val running = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "partial",
            ),
        )
        val terminalWithoutCode = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","output":"done"}""",
            ),
        )
        val background = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"background":true,"job_id":"job-1","state":"running"}""",
            ),
        )

        assertEquals(ShellPresentationStatus.Executing, shellPresentationStatus(running))
        assertEquals(
            ShellPresentationStatus.Background("job-1"),
            shellPresentationStatus(background),
        )
        assertEquals(
            ShellPresentationStatus.Exit(code = null),
            shellPresentationStatus(terminalWithoutCode),
        )
        assertEquals("done", shellOutputText(terminalWithoutCode))
    }

    @Test
    fun recoveredForegroundShellIsStoppedInsteadOfExitWithoutCode() {
        val recovered = RunRecoveryPolicy.stopIncompleteTools(
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "execute_shell_command",
                    toolState = ToolExecutionStates.RUNNING,
                ),
            ),
        ).single()
        val presentation = ToolPresentationResolver.resolve(recovered)

        assertEquals(ToolPresentationState.STOPPED, presentation.state)
        assertEquals(ShellPresentationStatus.Stopped, shellPresentationStatus(presentation))
    }

    @Test
    fun shellOutputFallsBackToSeparateStdoutAndStderr() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """
                    {"type":"execute_shell_command","exit_code":2,"stdout":"out","stderr":"err"}
                """.trimIndent(),
            ),
        )

        assertEquals("out\nerr", shellOutputText(presentation))
        assertEquals(ShellPresentationStatus.Exit(2), shellPresentationStatus(presentation))
    }

    @Test
    fun legacyConnectingProgressIsNotRenderedAsCommandOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "Connecting to tinybox",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(null, shellOutputText(presentation))
    }

    @Test
    fun mcpTitleUsesResolvedNameAndLegacyFallbackNeverLeaksRoutingParts() {
        assertEquals(
            "Read Image",
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image_a1b2c3",
                resolvedName = "read_image",
            ),
        )
        assertEquals(
            "Read Image",
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image_a1b2c3",
                resolvedName = null,
            ),
        )
        assertNull(
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image",
                resolvedName = null,
            ),
        )
    }

    @Test
    fun slashQualifiedMcpTitlePreservesPathAndInnerCasing() {
        assertEquals("Blender Mcp/Execute Code",
            mcpToolDisplayName(null, "Blender Mcp/execute Code"))
        assertEquals("Blender Mcp/Execute Code",
            mcpToolDisplayName(null, "blender_mcp/execute_code"))
        assertEquals("MCP/ReadURL", mcpToolDisplayName(null, "MCP/readURL"))
        assertEquals("Blender Mcp/", mcpToolDisplayName(null, "blender_mcp/"))
        assertEquals("Blender Mcp/Execute Code", fallbackToolDisplayName("blender_mcp/execute_code"))
        assertEquals("Blender Mcp/Execute Code", fallbackToolDisplayName("Blender Mcp/execute Code"))
        assertEquals("MCP/ReadURL", fallbackToolDisplayName("MCP/readURL"))
        assertEquals("Blender Mcp/", fallbackToolDisplayName("blender_mcp/"))
        assertEquals("Read  File", fallbackToolDisplayName("read__file"))
    }

    @Test
    fun mcpStructuredResultIsParsedIndependentlyFromProtocolText() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "mcp_server123_inspect_a1b2c3",
                toolResult = """Human summary

                    {"value":7}
                """.trimIndent(),
                toolResultText = "Human summary",
                toolStructuredResult = """{"value":7}""",
                toolTarget = "Filesystem",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(ToolKind.MCP, presentation.kind)
        assertEquals(
            7,
            ((presentation.result as JsonObject)["value"] as JsonPrimitive).int,
        )
        assertEquals("Human summary", presentation.rawTextResult)
        assertEquals("""{"value":7}""", presentation.rawStructuredResult)
        assertEquals("Filesystem", presentation.device)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }

    @Test
    fun waitForJobResolvesAsShellJobAndKeepsFullOutput() {
        val running = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "wait_for_job",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "streaming output",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(ToolKind.SHELL_JOB_WAIT, running.kind)
        assertEquals(ToolPresentationState.RUNNING, running.state)
        assertEquals("streaming output", shellOutputText(running))

        val terminal = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "wait_for_job",
                toolArgs = """{"job_id":"job-9"}""",
                toolResult = """{"type":"wait_for_job","job_id":"job-9","result":{"state":"succeeded","exit_code":0,"output":"done"}}""",
            ),
        )

        assertEquals(ToolKind.SHELL_JOB_WAIT, terminal.kind)
        assertEquals(ToolPresentationState.COMPLETED, terminal.state)
        assertEquals(0, terminal.exitCode)
        assertEquals("done", shellOutputText(terminal))
        assertEquals("job-9", terminal.jobId)

        val timedOut = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "wait_for_job",
                toolArgs = """{"job_id":"job-9"}""",
                toolResult = """{"type":"wait_for_job","job_id":"job-9","state":"running","timed_out":true,"output":"partial"}""",
            ),
        )

        assertEquals(ToolPresentationState.BACKGROUND_RUNNING, timedOut.state)
        assertEquals(ShellPresentationStatus.Background("job-9"), shellPresentationStatus(timedOut))
        assertEquals("partial", shellOutputText(timedOut))
    }

    @Test
    fun truncatedFileReadWithPartialContentRemainsCompleted() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_read",
                toolArgs = """{"path":"/tmp/large.txt","offset":0}""",
                toolResult = """{"type":"file_read","path":"/tmp/large.txt","content":"partial content","lines":1,"total_lines":0,"total_bytes":1048577,"returned_bytes":15,"offset":0,"limit":1048576,"truncated":true}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )
        val result = presentation.result as JsonObject

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals("partial content", (result["content"] as JsonPrimitive).content)
        assertEquals("true", (result["truncated"] as JsonPrimitive).content)
    }

    @Test
    fun truncatedConversationSearchCountsCompletedResults() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "search_conversations",
                toolArgs = """{"query":"x"}""",
                toolResult = """{"type":"search_conversations","query":"x","count":3,"results":[{"title":"A","match_count":1,"messages":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(1, presentation.count)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }

    @Test
    fun truncatedConversationSearchWithoutCountCountsCompletedResults() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "search_conversations",
                toolResult = """{"type":"search_conversations","query":"x","results":[{"title":"A","match_count":1,"messages":[]},{"title":"B","match_count":1,"messages":[]}""",
            ),
        )

        assertEquals(2, presentation.count)
    }

    @Test
    fun conversationListCountUsesReturnedRowsNotDurableTotal() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "list_conversations",
                toolResult = """{"type":"list_conversations","total":99,"conversations":[{"id":"a"},{"id":"b"}]}""",
            ),
        )

        assertEquals(2, presentation.count)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }

    @Test
    fun emptyConversationListPageIsEmptyDespiteDurableTotal() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "list_conversations",
                toolResult = """{"type":"list_conversations","total":99,"conversations":[]}""",
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun conversationSearchCountUsesReturnedResultsWhenCountFieldDisagrees() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "search_conversations",
                toolResult = """{"type":"search_conversations","count":3,"results":[{"title":"A"}]}""",
            ),
        )

        assertEquals(1, presentation.count)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }

    @Test
    fun fileReadErrorEnvelopeFailsInsteadOfAnEmptyCard() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_read",
                toolArgs = """{"path":"/tmp/missing.txt"}""",
                toolResult = """{"error":"not_found","message":"File not found: /tmp/missing.txt"}""",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("File not found: /tmp/missing.txt", presentation.errorMessage)
    }

    @Test
    fun declaredPlainTextToolFailureFailsEvenForContentLessToolKinds() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_read",
                toolArgs = """{"path":"/tmp/x.txt"}""",
                toolResult = "Error: unexpected end of stream",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("Error: unexpected end of stream", presentation.errorMessage)
    }

    @Test
    fun explicitFailedFlagFailsAndUsesItsMessageText() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "view_image",
                toolArgs = """{"path":"/tmp/x.png","server":"tinybox"}""",
                toolResult = """{"failed":true,"message":"Device is offline"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("Device is offline", presentation.errorMessage)
    }

    @Test
    fun shellTransportFailureFailsWithoutAnExitCodeStatus() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf done","server":"tinybox"}""",
                toolResult = """{"error":"error","message":"unexpected end of stream"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("unexpected end of stream", presentation.errorMessage)
        assertEquals(
            ShellPresentationStatus.Failed(code = null, message = "unexpected end of stream"),
            shellPresentationStatus(presentation),
        )
    }

    @Test
    fun failedWireStateOverridesAnEmptyLookingResult() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_grep",
                toolArgs = """{"pattern":"x"}""",
                toolResult = """{"type":"file_grep","matches":[]}""",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertNull(presentation.errorMessage)
    }

    @Test
    fun genuinelyEmptyFileReadIsStillEmpty() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_read",
                toolArgs = """{"path":"/tmp/empty.txt"}""",
                toolResult = """{"type":"file_read","path":"/tmp/empty.txt","content":"","lines":0}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(ToolPresentationState.EMPTY, presentation.state)
        assertNull(presentation.errorMessage)
    }
    @Test
    fun malformedStandInPresentsTheToolAndArgumentsTheModelSent() {
        val sent = """[{"question":"Pick one","options":["a","b"]}]"""
        val wrapper = JsonObject(
            mapOf(
                "error" to JsonPrimitive("arguments were not a valid JSON object"),
                "original_name" to JsonPrimitive("ask_user"),
                "original_arguments" to JsonPrimitive(sent),
            ),
        ).toString()
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "agora_malformed_tool_call",
                toolArgs = wrapper,
                toolResult = "Error: the previous tool call was malformed and was not executed",
                toolState = ToolExecutionStates.FAILED,
            ),
        )
        assertEquals("ask_user", presentation.toolName)
        assertEquals(sent, presentation.rawArguments)
        assertEquals(ToolKind.UNKNOWN, presentation.kind)
        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(
            "Error: the previous tool call was malformed and was not executed",
            presentation.errorMessage,
        )
    }
    @Test
    fun malformedStandInWithoutCarriedOriginalsKeepsTheStandInItself() {
        val wrapper = """{"error":"duplicate tool call id"}"""
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "agora_malformed_tool_call",
                toolArgs = wrapper,
                toolResult = "Error: the previous tool call was malformed and was not executed",
                toolState = ToolExecutionStates.FAILED,
            ),
        )
        assertEquals("agora_malformed_tool_call", presentation.toolName)
        assertEquals(wrapper, presentation.rawArguments)
        assertEquals(ToolPresentationState.FAILED, presentation.state)
    }
}
