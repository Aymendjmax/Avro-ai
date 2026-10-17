# Context Compact requirements

Status: historical, non-authoritative product baseline, updated 2026-08-13.

The global message-generation, fresh-Run, UI-boundary, and Provider context-boundary contracts are
now owned by [`../message-generation.md`](../message-generation.md). This document preserves the
Compact requirements used by an earlier implementation baseline. It cannot override or create an
exception to the current global contract.

## 1. Context settings and estimation

Context owns the estimated-token budget, rollout visualization, automatic Compact switch, Compact model, editable prompt, and the count of recent logical messages preserved verbatim. An unset Compact model inherits the active conversation model.

The composer indicator, provider-visible context projection, threshold, and truncation use the same deterministic cross-provider estimator. It accounts for text, framing, images, tool names/arguments/results, and complete tool rounds. Exact provider tokenization remains model-specific, so the UI labels usage as estimated.

The indicator popup formats both values consistently: values below 1,000 are integers; values at or above 1,000 use a compact `k` unit. It must never render a mixed form such as `~1234 / 128 k tokens`.

A logical message is counted after canonical consecutive-role merging. Tool calls/results consume no logical-message slots and remain attached atomically to the surrounding assistant continuation. Truncation and Compact must never split a protocol round.

## 2. Non-destructive boundary

Compact persists one visible capsule in the conversation graph and never deletes original messages. Walking upward from the latest selected message, the nearest Compact whose generation ended normally with `SUCCESS` is the sole API context boundary; ERROR, STOPPED, and in-flight Compact rows are ignored. Deleting a successful capsule restores the preceding successful boundary while preserving the original graph.

Compact summarizes only the older prefix. The configured last N logical messages remain verbatim after the boundary, in original order and with complete tool rounds. Effective provider context is:

`Compact summary + preserved recent suffix`

If the older prefix is empty, Compact does not run.

## 3. Compact is a standard generation

Compact is an ordinary standard generation. Every automatic, manual, or Recompact admission creates a fresh Run and uses the same request/context builder, Provider execution, streaming/checkpoint, Stop/cancellation, overlay, queue, terminal settlement, and recovery pipeline as ordinary model generation. Compact-specific data is limited to its message identity/UI, haptic exclusion, selected frozen generation parameters, tools disabled, system prompt, retained-summary final text, and (for Recompact) the existing output row target. It must not create a reducer subtype, Provider runner, context builder, settlement path, or parallel composer lifecycle.

Composer invariants:

- While Compact generates and the draft/attachments are empty, the Send control shows the normal **Stop** state.
- If text or attachments are present, the control shows **Send**.
- Sending during Compact accepts the content into the normal FIFO guidance/message queue. It is not persisted into the selected graph early.
- After Compact reaches a legal terminal boundary, queued content drains through the ordinary real-send pipeline and starts a fresh Run automatically.
- Stop has its ordinary meaning: cancel the active Compact generation, settle coroutine and durable state, then drain retained queued guidance through the normal boundary.
- The generating-state activity dot used for an ordinary assistant response must not be projected as a second Compact-specific white dot.

Automatic pre-send Compact follows the same rule. When a user send crosses the threshold, create and run the Compact generation first, accept the user content into the normal queue exactly once, then automatically persist/send it after Compact completes. After a durable tool result, ordering is `Compact -> FIFO queued user message -> loop`; a no-input loop may continue only when no pending or claimed guidance exists.

No caller may hold a second conversation/automation lock while waiting for the generation slot. Every result is fenced by conversation, Run, pass, owner, and effect identity; stale results cannot mutate UI or graph state.

## 4. Scrolling and layout

When Compact begins while the conversation is attached to the bottom, request the ordinary generation follow-bottom behavior. Do not steal scroll position when the user is reading above the bottom.

Historical note: the earlier rule below treated Compact as assistant output for padding. It is
superseded by the current authoritative message-generation contract: Compact is the semantic tail
anchor, while the physical final rendered turn holds only the remaining minimum after measured
anchor-region content is deducted. This keeps all tail turns adjacent and leaves unused capacity only
after the complete tail.

For follow-bottom padding, a Compact capsule is treated as assistant output. It must not receive the user-message rule that pads enough space for a full blank viewport.

Deletion does not select or scroll to a target. The shared message-deletion pipeline accepts an explicit `scrollToTarget` Boolean: ordinary message callers may pass `true`; Compact deletion passes `false` and preserves the existing scroll position. The expensive post-overlay sequence of repeated target scrolls, height measurement, and frame stabilization must not run when the flag is false.

## 5. Capsule interaction

A newly created Compact capsule enters once with the shared draw-only fade animation and no scale change. Its outer padding, minimum height, icon slot, label line count, and action slot remain stable across progress and terminal states. Recomposition, branch restoration, and ordinary list refresh must not replay the entrance.

Opening capsule details uses the same loading contract as a thinking-segment bottom sheet:

1. show the bottom sheet immediately;
2. start message/detail loading asynchronously;
3. render content when ready;
4. show the established progress indicator only when loading exceeds its delay threshold.

Do not block sheet presentation on database/message loading.

The capsule overflow menu copies the ChatBottomBar/assistant-message dropdown surface exactly: rounded menu container, established typography and spacing, and an icon on every item. Delete uses the shared destructive red icon/text treatment.

## 6. Deletion

Compact deletion uses the ordinary message-delete UI and lifecycle: destructive confirmation, haptic acceptance, full-screen overlay, delayed circular progress indicator, and fade transitions. Only the database mutation is Compact-specific: delete that Compact row and rewire the adjacent graph nodes/boundary references.

The database operation must be bounded to the selected Compact identity and necessary neighbors. It must not perform unrelated graph reconstruction, generation cancellation, repeated layout measurement, or scroll-target stabilization. Completion/error/haptics and overlay cleanup remain owned by the shared deletion pipeline.

## 7. Manual and automatic entry points

The ChatBottomBar overflow menu exposes Compact and uses the saved Context model, prompt, and preserved-message count. Manual and automatic entry points share path resolution, canonicalization, request generation, persistence, queue behavior, Stop behavior, and terminal settlement.

Eligibility checks occur only at legal continuation boundaries and never race an active provider stream, tool commit, queue drain, or another generation. Duplicate Compact requests fail or defer without creating duplicate capsules.

## 8. Required verification

Regression coverage must include:

- token popup formatting below/above 1,000;
- manual Compact Stop/Send projection and absence of the extra white dot;
- send-during-Compact FIFO drain;
- threshold-triggered Compact followed by exactly one queued user send;
- tool continuation after Compact;
- attached and detached scrolling;
- assistant-style bottom padding;
- immediate bottom-sheet presentation and delayed progress;
- one-shot fade-only capsule entrance and stable vertical bounds across progress/terminal states;
- shared rounded/icon menu and destructive red Delete;
- Compact deletion with haptics/overlay, bounded rewiring, `scrollToTarget=false`, and preserved scroll position;
- Stop, cancellation, stale result, branch change, and process-recovery paths.
