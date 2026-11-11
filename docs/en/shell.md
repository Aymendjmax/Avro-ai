# Remote Shell

Open **Settings → Shell Devices** to configure Conch or SSH devices used by agentic tools.

## Conch

Configure the server URL, optional API key, and command-confirmation policy. With an API key, Avro fetches the server's signed public key, derives an ephemeral shared key, and sends authenticated AES-GCM requests. With a blank key, the application payload is plain JSON and transport confidentiality depends on HTTPS.

Foreground commands are created as durable server jobs and waited on for a bounded period. If the wait expires, the command is not killed or replayed: Avro returns a `job_id` that can be inspected, waited on, stopped, and acknowledged later. Conch tools also support remote read, write, edit, glob, grep, and image viewing.

## SSH

Configure host, port, user, authentication, and host-key verification/pinning. Direct SSH transport is separate from Avro's shared HTTP proxy.

## Confirmation

## Operating Modes & Shell Isolation

In Avro 2.3.0 and newer, conversations use dedicated operating modes:
- **Ask Mode (Conversational)**: Standard chat mode. Shell execution and terminal tools are explicitly disabled across all Ask conversations (including legacy conversations), preventing hallucinated command invocations and unwanted execution.
- **Work Mode (Autonomous Agent)**: Enables the local PRoot Alpine Linux sandbox, terminal execution (`execute_shell_command`), remote shells, FileStore attachments, and file manipulation tools. Switch to Work mode from the composer plus menu or Work mode chip when terminal capabilities are needed.

See [Agentic Tools](tools.md), [Network Proxy](proxy.md), and [Privacy & Security](privacy.md).
