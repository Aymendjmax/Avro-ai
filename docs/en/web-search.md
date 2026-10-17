# Web Search

Open **Settings → Web Search** to choose a search backend and result count.

## Providers

Avro supports Brave, Kagi, Serper, Tavily, SearXNG, and DuckDuckGo. Most require credentials; SearXNG requires the instance URL. DuckDuckGo is the default best-effort option and does not require an API key.

Web search is enabled by default. The default result count is 5 and can be set from 1–10.

## Data flow

Queries are sent directly to the selected search provider. Returned snippets and links can be stored in the conversation as tool data and sent to the chat model during the agentic loop. Provider policies, quotas, and availability are external to Avro and can change.

See [Agentic Tools](tools.md) and [Privacy & Security](privacy.md).
