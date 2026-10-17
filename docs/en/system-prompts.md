# System Prompts

Open **Settings → System Prompts** to manage reusable prompt configurations.

## Create and edit

New prompts start from either **Blank** or **Default**. The editor has three ordered templates:

- **System** defines the complete provider-visible system message.
- **User** defines ordinary user messages.
- **Assistant** defines ordinary assistant messages.

User and Assistant each contain exactly one structural `Prompt` item. It represents the original message body and cannot be deleted, moved, or inserted again. Text and variables can be added above or below it.

Current variables include `{time}`, `{date}`, `{sent_time}`, `{sent_date}`, `{active_memory}`, `{skill_catalog}`, `{current_model_id}`, and `{message_model_id}`. `{current_model_id}` is the model selected for the outbound request. `{message_model_id}` is resolved separately for each ordinary historical message from the model that created it, and is empty when that message has no model identity. The legacy `{model_id}` variable remains readable as an alias of `{current_model_id}` but is no longer offered for new templates. Every variable except `{active_memory}` is resolved immediately before each outbound provider request, including the initial request, tool continuations, and transport retries. `{active_memory}` is read once when a reply starts and stays fixed for every request of that reply, which keeps the prompt cache stable; edits made during the reply are visible through `read_active_memory` and enter the prompt on the next reply. Editor previews use example values and do not freeze future request values.

The selected structured System template fully owns the system prompt for ordinary generation. Avro does not append hidden memory, skill, runtime, or tool-guidance text. Access settings control whether protected variable values can resolve and whether tools are available.

User and Assistant templates apply only to ordinary conversation messages. Tool messages, context compaction, title generation, and other special generation paths keep their dedicated formats.

## Manage and select

Prompts can be edited, duplicated, deleted, and marked as the global default. A conversation can inherit that default or select another saved prompt.

The built-in Default template is defined by the app and can change with product behavior; it is not one of several fictional category libraries.
