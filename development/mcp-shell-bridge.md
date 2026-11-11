# MCP & Shell File Bridge Contract (`McpFileBridge`)

## Overview

The `McpFileBridge` links Model Context Protocol (MCP) server tools with the on-device Alpine Linux sandbox filesystem (`/home/`), allowing seamless file and artifact exchange between autonomous shell commands and external MCP tool providers.

## File Reference Protocol (`file:<path>`)

To allow external MCP servers (which communicate over stdio or SSE/HTTP) to reference local files safely without embedding full megabytes of binary or text data in JSON-RPC payloads, Avro defines the `file:<path>` URI protocol:

1. **Input Arguments**:
   - When an MCP tool accepts a file path argument or receives `file:<path>`, `McpFileBridge` resolves the path against `/home/uploads/<chatId>/` and `/home/outputs/<chatId>/`.
   - Traversal attacks (`..`) are strictly rejected.
   - If an MCP tool expects raw file text content, the bridge reads the file from the sandbox and supplies its string content.

2. **Output Artifacts**:
   - When an MCP tool returns file content or base64 images, `McpFileBridge` saves the artifact directly into `/home/outputs/<chatId>/<toolName>_<filename>`.
   - The tool result returned to the model substitutes the binary payload with a `file:/home/outputs/<chatId>/...` URI.
   - The model can then inspect the generated file using `view_image`, edit it with `execute_shell_command`, or present it to the user with `present_files`.

## Quick Mention Picker (`/` and `@`)

1. **Slash (`/`) for Skills**: Floating completion popup displaying available Agent Skills with instant search and inline description.
2. **At (`@`) for MCP**: Floating completion popup displaying configured MCP servers and their available tools.
3. **Per-Turn Dynamic Activation**: Mentioning an MCP server with `@server` selectively activates that server's tools for the upcoming generation turn in `GenerationContext`.
