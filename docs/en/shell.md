# Remote Shell

Open **Settings → Shell Devices** to configure Conch or SSH devices used by agentic tools.

## Conch

Configure the server URL, optional API key, and command-confirmation policy. With an API key, Avro fetches the server's signed public key, derives an ephemeral shared key, and sends authenticated AES-GCM requests. With a blank key, the application payload is plain JSON and transport confidentiality depends on HTTPS.

Foreground commands are created as durable server jobs and waited on for a bounded period. If the wait expires, the command is not killed or replayed: Avro returns a `job_id` that can be inspected, waited on, stopped, and acknowledged later. Conch tools also support remote read, write, edit, glob, grep, and image viewing.

## SSH

Configure host, port, user, authentication, and host-key verification/pinning. Direct SSH transport is separate from Avro's shared HTTP proxy.

## Confirmation

Commands can require confirmation or be always allowed per configured server. Treat both shell types as high-trust capabilities: the remote account's permissions define what the model can change.

See [Agentic Tools](tools.md), [Network Proxy](proxy.md), and [Privacy & Security](privacy.md).
