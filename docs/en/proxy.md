# Network Proxy

Open **Settings → Network Proxy** to route Avro's shared HTTP-client traffic through a configured proxy.

## Scope

The proxy can affect provider requests, model synchronization, web search, MCP over HTTP, update checks, and explicitly submitted rating/crash reports when those paths use the shared client. It does not transparently proxy:

- direct SSH connections
- local llama.cpp inference
- processes and networking inside the Alpine sandbox

The destination may still see traffic metadata, and the proxy operator can observe traffic according to the transport's encryption.

## Authentication and bypass

Configure the proxy type, host, port, optional username/password, and bypass rules accepted by the UI. Test the route before relying on it. A proxy password is included in an Avro export only if the secrets/API-key category is explicitly selected.

See [Privacy & Security](privacy.md).
