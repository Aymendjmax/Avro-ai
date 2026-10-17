# Conversation Search

Open **Settings → Conversation Search** to configure keyword and semantic access to previous conversations.

## Access

**Access Past Conversations** is enabled by default. Disable it to prevent the model's conversation-search tool from retrieving prior conversation content. Active-memory and saved-memory permissions are configured separately.

## Caching

**Auto-Cache New Messages** is enabled by default. With an active embedding model, Avro indexes newly persisted searchable messages. When you switch the active embedding model, it also fills that model's missing message embeddings while Auto-Cache is enabled.

When automatic caching is off, new messages remain uncached until you start caching manually. **Show Uncached Notification** is visible only in this state and controls reminders that can offer a **Cache now** action.

## Search Methods

Choose **Keyword** or **Semantic (RAG)** independently for:

- **Model Search Method**, used by model tool calls;
- **Manual Search Method**, used by the search bar in the conversation drawer.

Semantic search cannot be selected until at least one embedding model is configured. Keyword search does not require an embedding cache.

## Embedding Models

Add remote embedding endpoints or import local GGUF embedding models. One model is active at a time. Each row shows whether the model is local or remote and how many searchable messages are cached for it.

Use **Cache** to fill missing embeddings. A queued cache with no trustworthy total shows **Loading**. Once work starts, the row shows one generation's remaining count and determinate progress until the final count and ledger refresh completes. A failed worker keeps its last reliable remaining count and offers **Retry**. Successful completion settles directly to **Re-cache**, which asks for confirmation before rebuilding that model's cache. Changing the active model can also fill missing entries when Auto-Cache is enabled.

Remote embedding models receive the text that must be embedded. The index and cache metadata are stored locally.

## Advanced Retrieval

Configure:

- a context range from 4–32 conversation steps;
- 5–30 returned results;
- a similarity threshold from 0–1 (default 0.5).

See [Embedding / RAG](embedding.md), [Memory & Cache](memory.md), and [Privacy & Security](privacy.md).
