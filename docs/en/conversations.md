# Conversations

Avro stores conversations as message trees. Editing an earlier message or regenerating a reply creates another branch without deleting the other branch.

## Create and navigate

Use the conversation drawer to create, rename, search, pin, archive, or delete conversations. Inside a chat, branch controls let you move between sibling replies where alternatives exist.

## Send and generate

The bottom bar selects the model and shows estimated context occupancy. During a normal generation—including Compact—the send control follows the same generation state: it becomes **Stop** when no draft or attachment is waiting, and remains **Send** when content can be queued. Queued content is sent automatically after the active generation finishes.

Long conversations use the budget and Compact behavior configured in **Settings → Context**. See [Context](context.md).

## Titles and prompts

Conversation titles can be generated automatically using [Title Generation](title-generation.md). A conversation may inherit the default [System Prompt](system-prompts.md) or select another one.

## Attachments

The composer can attach supported images, videos, PDFs, and files. Provider capabilities and enabled transcription/generation models determine how each attachment is processed.
