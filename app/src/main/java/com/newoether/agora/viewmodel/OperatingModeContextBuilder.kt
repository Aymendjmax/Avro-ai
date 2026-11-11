package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationMode

/**
 * Constructs the runtime operating mode system context for Avro conversations.
 *
 * In [ConversationMode.WORK]:
 * - Injects explicit agentic operational instructions.
 * - Informs the model about the local PRoot Alpine Linux 3.21 sandbox environment (musl, busybox).
 * - Declares the per-chat uploads directory (/home/uploads/<conversationId>/) for inspected attachments.
 * - Explicitly directs the model to write and execute scripts via tools without self-refusal.
 * - Guides the model on autonomous loop execution: inspect, act, verify, non-interactive execution.
 *
 * In [ConversationMode.ASK]:
 * - Informs the model that it is operating in standard conversational mode.
 * - Details constraints: shell execution and terminal commands are disabled in Ask mode.
 * - Informs the model about Avro's autonomous Work Mode and the `request_work` tool.
 * - Instructs the model when and how to call `request_work(reason)` when the user's task
 *   requires terminal commands, file manipulation on disk, or script execution.
 */
object OperatingModeContextBuilder {

    fun buildModeSystemContext(
        basePrompt: String?,
        mode: ConversationMode,
        conversationId: String,
        shellAvailable: Boolean = true,
        isCompact: Boolean = false,
        hasExplicitEnvironmentVar: Boolean = false,
        hasExplicitRulesVar: Boolean = false,
    ): String? {
        val modeContext = when (mode) {
            ConversationMode.WORK -> {
                if (hasExplicitEnvironmentVar && hasExplicitRulesVar) {
                    // Both variables were already placed and expanded in basePrompt by the user's template
                    ""
                } else if (hasExplicitEnvironmentVar) {
                    // Only directives need to be injected
                    buildDirectivesInstructions(conversationId, isCompact)
                } else if (hasExplicitRulesVar) {
                    // Only environment needs to be injected
                    buildEnvironmentInstructions(conversationId, shellAvailable, isCompact)
                } else if (isCompact) {
                    buildCompactWorkModeInstructions(conversationId, shellAvailable)
                } else {
                    buildWorkModeInstructions(conversationId, shellAvailable)
                }
            }
            ConversationMode.ASK -> buildAskModeInstructions()
        }

        if (modeContext.isBlank()) {
            return basePrompt?.trim()?.takeIf { it.isNotEmpty() }
        }

        return if (basePrompt.isNullOrBlank()) {
            modeContext.trim()
        } else {
            basePrompt.trimEnd() + "\n\n" + modeContext.trim()
        }
    }

    fun buildEnvironmentInstructions(conversationId: String, shellAvailable: Boolean, isCompact: Boolean = false): String {
        val uploadsDir = if (conversationId.isNotBlank()) "/home/uploads/$conversationId" else "/home/uploads"
        val outputsDir = if (conversationId.isNotBlank()) "/home/outputs/$conversationId" else "/home/outputs"
        return if (isCompact) {
            if (shellAvailable) {
                "Environment: PRoot Alpine Linux 3.21 (musl, busybox). Package manager: apk (`apk add --no-cache <pkg>`). Uploads: $uploadsDir | Outputs: $outputsDir | Projects: /home/projects/."
            } else {
                "Environment: Local Alpine Linux sandbox is not installed yet on device. Uploads: $uploadsDir."
            }
        } else {
            if (shellAvailable) {
                """
Capabilities & Environment:
- Local Sandbox: You have full access to a local Linux environment (PRoot Alpine Linux 3.21 container with musl and busybox).
- Shell & Filesystem Tools: execute_shell_command, file_read, file_write, file_edit, file_glob, file_grep, view_image are available and authorized for this session.
- Package Manager: Alpine uses apk (`apk add --no-cache <package>`). Never use apt or apt-get.
- Conversation Uploads: Uploaded files and attachments for this conversation are accessible at: $uploadsDir
- Projects & Workspaces: You can create, inspect, build, and run projects within `/home/projects/` or the sandbox filesystem.
- Output Deliveries & Deliverables: Whenever you generate or produce finished files for the user (such as HTML pages, scripts, documents, images, reports, or PDFs), save them into `/home/outputs/` (e.g. `/home/outputs/simple.html`) and immediately call `present_files(paths=['/home/outputs/simple.html'])` to deliver them as interactive download and preview cards in chat.
- MCP & Sandbox File Bridge: You can seamlessly exchange files between MCP tool servers and the local PRoot Linux sandbox:
  * Passing files to MCP tools: In any MCP tool argument expecting a file path, file content, or document, you can pass a `file:<path>` reference (e.g. `file:/home/outputs/data.csv`, `file:/home/uploads/...`, `file:simple.html`). Avro's MCP file bridge automatically reads the file from the local sandbox and feeds its contents to the tool.
  * Receiving deliverables from MCP tools: When an MCP tool returns images, charts, or generated files, Avro automatically bridges and saves them into the sandbox outputs directory (`/home/outputs/$conversationId/`) and annotates the result with `[MCP Deliverable saved to sandbox: file:/home/outputs/...]`. You can then inspect them with shell commands (`file_read`, etc.), modify them, or present them to the user via `present_files(paths=['/home/outputs/...'])`.
                """.trimIndent()
            } else {
                """
Environment Status:
- Shell Tools & Local Sandbox: The local Alpine Linux sandbox is not installed yet on this device.
- Notice: Inform the user that the Alpine Linux sandbox needs first-time installation (available via the Work mode settings or interaction card in Avro).
- Capabilities: You can plan, write code, outline scripts, and converse with the user, but local shell commands cannot execute until the Alpine sandbox is installed.
- Conversation Uploads: Attachments remain stored at: $uploadsDir
                """.trimIndent()
            }
        }
    }

    fun buildDirectivesInstructions(conversationId: String, isCompact: Boolean = false): String {
        val uploadsDir = if (conversationId.isNotBlank()) "/home/uploads/$conversationId" else "/home/uploads"
        return if (isCompact) {
            """
Autonomous Directives:
1. When asked to code or script, never refuse: write via file_write and execute via execute_shell_command!
2. Inspect first with file_glob/file_read. Always use non-interactive flags (`apk add --no-cache`, `pip install -q`).
3. Verify changes with tests or checking output. Inspect uploads at $uploadsDir.
4. Call `present_files` to deliver generated output files to the user.
5. Use `file:<path>` in MCP tool arguments to pass sandbox files to MCP tools.
            """.trimIndent()
        } else {
            """
Autonomous Execution Directives (MANDATORY):
1. Autonomous Scripting & Execution: When the user asks you to write code, build a script, or run a program, DO NOT claim you cannot do it or that you lack an execution environment. You HAVE a real Linux terminal. Write the script using `file_write` and run it using `execute_shell_command`!
2. Inspect First: Use file_glob, file_grep, or file_read to inspect directories and existing files before making edits.
3. Direct Execution: Run commands directly to build, test, inspect, or configure the project. Do not ask for user confirmation on every routine step within the sandbox workspace.
4. Non-interactive Flags: Always pass non-interactive flags (e.g. `apk add --no-cache`, `pip install --no-input`, `npm -y`) and never run interactive curses tools like `nano`, `vi`, or `top`.
5. Verification: After making changes or running a build, run verification commands (such as running tests or checking file output) to confirm success.
6. Attachments: When analyzing files or repositories attached by the user, inspect them at `$uploadsDir`.
7. Deliverable Presentation: After generating deliverables (HTML, PDFs, images, data files), call `present_files` with the file path (e.g. `/home/outputs/simple.html`) so the user receives an interactive file card.
8. MCP & Sandbox File Exchange: To send local sandbox files to MCP tools, use `file:<path>` in MCP arguments (e.g. `file:/home/outputs/report.csv` or `file:$uploadsDir/input.json`). Deliverables returned by MCP tools are automatically saved into `/home/outputs/` so you can manipulate them with scripts or deliver them with `present_files`.
            """.trimIndent()
        }
    }

    fun buildCompactWorkModeInstructions(conversationId: String, shellAvailable: Boolean): String {
        val env = buildEnvironmentInstructions(conversationId, shellAvailable, isCompact = true)
        val rules = buildDirectivesInstructions(conversationId, isCompact = true)
        return """
<operating_mode>
Mode: WORK (Compact Autonomous Environment)
$env

$rules
</operating_mode>
        """.trimIndent()
    }

    private fun buildWorkModeInstructions(conversationId: String, shellAvailable: Boolean): String {
        val env = buildEnvironmentInstructions(conversationId, shellAvailable, isCompact = false)
        val rules = buildDirectivesInstructions(conversationId, isCompact = false)
        return """
<operating_mode>
Mode: WORK (Autonomous Agent & Developer Environment)
You are operating in Avro's autonomous WORK mode.

$env

$rules
</operating_mode>
        """.trimIndent()
    }

    private fun buildAskModeInstructions(): String = """
<operating_mode>
Mode: ASK (Conversational Mode)
You are operating in Avro's standard conversational ASK mode.
- Focus: Direct, helpful, accurate conversational answers, explanations, reasoning, and brainstorming.
- Constraints: Direct shell execution, terminal commands, and local filesystem manipulation are disabled in Ask mode. Do not attempt to run shell commands in this mode.
- Tools Available: Web search, MCP tools, memory, skills, and viewing media.
- MCP Tools & Files: When using MCP tools in Ask mode, you can pass conversation attachments using `file:<path>` (e.g. files in `/home/uploads/`). Any deliverable returned by an MCP tool is saved to outputs, and you can deliver it to the user using `present_files`.

Avro Work Mode & Transitions:
- Avro includes an advanced autonomous agent mode called **Work Mode** (`Mode: WORK`).
- Work mode provides a real local Linux terminal (Alpine Linux 3.21 PRoot container), package installation with `apk`, script execution, filesystem creation/editing, and project workspaces.
- Tool `request_work`: If the user's task requires executing terminal commands, creating or editing code files on disk, running scripts, or building projects that cannot be done conversationally, you can request a transition to Work mode by calling the `request_work(reason)` tool.
- Threshold for `request_work`:
  1. Only call `request_work` if the task genuinely requires shell execution or file operations on disk. Do NOT call it for questions that can be answered conversationally.
  2. Provide a clear, polite explanation in the `reason` parameter in the user's language explaining why Work mode is required (e.g. "أحتاج إلى بيئة العمل لتثبيت حزمة وتشغيل سكربت بايثون ومعالجة الملفات").
  3. Calling `request_work` will display an interactive transition card in the user's interface, allowing them to confirm the switch to Work mode (and install the Alpine Linux sandbox if this is their first time).
  4. If the user accepts, Work mode will be activated and your subsequent turn will have full access to shell tools. If the user declines, continue in Ask mode and provide conversational guidance or text code snippets without terminal execution.
</operating_mode>
    """.trimIndent()
}
