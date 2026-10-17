# Agentic Tools

Avro can let a model call tools across multiple generation passes.

## Available capabilities

Depending on settings, build, and provider support, tools include:

- web search
- image generation
- active and saved memory
- past-conversation search
- MCP servers
- Tasks and Loops automation
- remote Conch/SSH commands, durable job management, file operations, and `view_image`
- the F-Droid Alpine sandbox

## Permissions and defaults

Web search, active memory, saved memory, past-conversation access, and the global shell permission are enabled by default. Shell calls still require a configured device, and its confirmation policy remains authoritative. Automation tools are disabled by default. MCP availability is controlled per server and per tool.

Tool calls and results become part of the conversation protocol and can be sent to the selected model on subsequent passes. External tools receive the arguments needed for their call. Review each server and permission before enabling it.

Image-generation and embedding credentials come from their selected providers; they are not separate universal tool secrets. See [MCP](mcp.md), [Automation](automation.md), [Shell](shell.md), and [Privacy & Security](privacy.md).
