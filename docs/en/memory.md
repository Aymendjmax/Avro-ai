# Memory & Cache

Avro has two model-accessible memory sources:

- **Active memory**: compact information intended to be available across conversations.
- **Saved memories**: persistent memory files that tools can read and update.

Access to active memory and saved memories is enabled by default and can be controlled independently. Conversation-history access is configured under **Settings → Conversation Search**, not on the memory page.

Memory data is stored locally until you delete it or include it in a request. When an enabled model/tool uses memory, the relevant content can be sent to that model provider. Clearing a cache is different from deleting saved memory; review the confirmation text before acting.

See [Conversation Search](search.md), [Agentic Tools](tools.md), and [Privacy & Security](privacy.md).
