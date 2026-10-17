# Context

Context settings control how much conversation history is available to each generation and what Avro does when that history approaches the configured token budget.

## Context Window

**Default Context Window** is a token budget, not a message count. Available presets range from **4K** to **1M** tokens; the default is **32K**.

The budget includes the provider-visible request: system instructions, enabled tool definitions, attachment text or stored image transcription, and the selected conversation branch. Avro estimates those costs locally, then keeps the newest complete messages that fit. A complete tool-call round is never split.

The context indicator in the chat bottom bar shows estimated usage. The estimate can differ from the provider's final token accounting because tokenizers and provider-side formatting vary.

A conversation can override the default context window from its advanced settings.

## Visualize Context Roll-Out

Enable **Visualize Context Roll-Out** to dim messages that are outside the current provider-visible window. The messages remain in the conversation and can become visible to the model again after switching branches or increasing the budget.

## Context Compact

Context Compact summarizes older context into a durable Compact capsule while keeping recent messages verbatim. Original messages are not deleted.

- **Automatic compact** starts before a send would overflow the active budget.
- **Compact model** chooses a dedicated enabled model. Leave it on the current model to follow the conversation.
- **Recent messages to keep** preserves 0–20 recent messages verbatim after the Compact boundary.
- **Compact prompt** controls the summary instructions.

You can also open the context indicator in the chat bottom bar and choose **Compact context**. Manual Compact uses the same generation controls as a normal response: an empty composer shows Stop, while text or attachments can be sent into the queue and run after Compact finishes.

## Compact Capsules

Tap a Compact capsule to read its summary. The overflow menu can recompact or delete the capsule.

Deleting a Compact capsule removes only that summary boundary and reconnects the surrounding branch. It does not delete the original conversation messages.

!!! tip
    Use a smaller budget to reduce request size, or use Compact when a long conversation needs to preserve important earlier state without sending every old message verbatim.

!!! note
    Compact is a generated summary. Review important facts in the capsule before relying on it for long-running work.
