# Local Model Runtime Contract

Status: authoritative embedded llama.cpp lifecycle contract, 2026-08-28.

This document owns process-wide admission, native model residency, identity changes, cancellation,
and idle offload for Avro's embedded llama.cpp Chat and Embedding paths. Conversation/Run lifecycle
remains owned by [message-generation.md](message-generation.md), while portability of the device-local
retention setting remains owned by [import-export.md](import-export.md).

## 1. Canonical owners

- `LocalModelRuntime` is the one process-wide owner of embedded llama.cpp admission and the one
  resident native model/context.
- `LocalModelTaskQueue` is the one FIFO admission boundary shared by Local Chat, Local title
  generation, and Local Embedding work.
- `LlamaChatEngine` owns a resident Chat native handle and its replaceable multimodal projector
  substate. `LlamaEngine` owns a resident Embedding native handle only while the runtime identifies
  that resident as Embedding.
- `AppContainer` binds the one app-lifetime idle-retention settings flow to the runtime and supplies
  Android's process native-library directory for backend initialization.

No caller may load, unload, reset, replace, or generate with an embedded model outside this owner.
Remote Providers, including a PC-hosted Qwen endpoint, do not enter this queue.

### Android CPU backend initialization

The Android build uses shared llama.cpp/ggml libraries and dynamic CPU backends. It packages the
seven upstream Android ARM variants from `android_armv8.0_1` through `android_armv9.2_2`; the
`android_armv8.0_1` module is the compatible arm64 baseline when no higher-scoring feature set is
available. KleidiAI, Vulkan, and OpenMP remain disabled.

`AppContainer` passes `applicationInfo.nativeLibraryDir` once to `LocalModelRuntime`. The runtime
canonicalizes that directory and calls the existing `LlamaEngine` JNI owner, which loads the best
compatible CPU module with `ggml_backend_load_all_from_path` before initializing llama.cpp. Chat and
Embedding model-load entry points must not independently load or initialize backends. Repeated
initialization from the same process directory is a no-op; a different directory is an invariant
violation. If no compatible CPU backend loads, Local Chat reports its ordinary model-load failure and
Local Embedding returns no result without making application startup fail.

## 2. Resident identity and switching

Exactly one of these identities may be resident:

- `Chat(canonicalModelPath, nCtx)`;
- `Embedding(canonicalModelPath)` using the fixed native Embedding context parameters.

Chat and Embedding are different identities even when their canonical model paths match. Chat
sampling values such as temperature, top P, frequency/presence penalties, and maximum output tokens
do not construct the native context and therefore do not change identity.

New Local Chat model records created through Settings or onboarding default to `nCtx=16384` and
`maxTokens=1024`. Existing records are not migrated: the serialized `LocalChatModelConfig` fallback
for a missing legacy `nCtx` remains 2048, and an explicitly stored context size remains unchanged.

A task requesting the current identity reuses its resident model. Reused Chat identity also retains
the native context's proven text KV prefix. Reused Embedding identity clears per-input context memory
through the native Embedding path. A different path, mode, or Chat `nCtx` closes the old resident
completely before the replacement load begins. If replacement loading fails, no model remains
resident and the request fails through its ordinary Local error path.

The multimodal projector is replaceable Chat substate rather than process identity. It is loaded only
for an image request, reused only for the same projector path, replaced when that path changes, and
never permits concurrent mutation of the resident Chat engine.

### Text KV prefix ownership

The resident native `ChatHandle` owns both its llama.cpp context memory and a ledger containing only
token IDs whose decode completed successfully in that context. A new text prompt reuses their token
longest-common-prefix, removes memory positions at and after the divergence, and decodes only the
uncached suffix. A complete prompt match retains all but the final token so that this request decodes
at least one token and obtains current sampling logits.

The complete newly rendered prompt must fit `nCtx` before the resident cache is modified. A token or
batch enters the ledger only after its corresponding `llama_decode` succeeds. Cancellation between
successful batches may therefore retain the known prefix. Any nonzero decode result can leave a
partially processed ubatch, so it clears both native memory and the ledger. If partial sequence
removal is unsupported, fails, or leaves memory inconsistent with the retained prefix, the same full
clear occurs before decoding the prompt from zero.

Image embeddings cannot be represented by the text token ledger. Template, image-read, allocation,
and multimodal tokenization failures that occur before native evaluation leave the prior text cache
untouched. Immediately before mtmd evaluation, the runtime clears the text cache; every terminal path
after evaluation starts clears native memory and the ledger again. The next text request after an
image request therefore starts with an empty context.

## 3. Chat templates and thinking

Chat prompt rendering uses the explicit template embedded in the GGUF through llama.cpp's official
`llama-common` Jinja owner. A missing, invalid, or inapplicable model template fails the request; the
runtime must not substitute ChatML, a model-family prompt, or another generic fallback. The parsed
template bundle is Chat resident substate and is released before its model.

Each request passes its effective `thinkingEnabled` value into the model template. This value may
change the rendered prompt but does not construct the model/context or change resident identity.
Native template parsing remains authoritative for typed tool-call events. It must not bypass the
shared incremental thinking parser for ordinary text: model-emitted reasoning delimiters, including
supported space-bearing channel forms, are recovered across arbitrary callback chunk boundaries while
matching markers inside Markdown inline or fenced code remain literal.

Each Local request also carries its effective temperature, top P, maximum output tokens, frequency
penalty, and presence penalty into both text and multimodal native generation. Nullable penalties
become neutral zero. Both native sampler chains use llama.cpp's penalties sampler with its default
64-token history window, neutral repeat penalty `1.0`, and the captured frequency/presence values;
they must not replace those values with a repeat-penalty approximation.

## 4. Strict FIFO admission

Every submitted Local task is counted as queued-or-active before it waits for the process permit.
The permit is fair FIFO: one complete Local request owns it from identity selection/load through all
native work, callbacks, and request cleanup. There is no concurrent Chat/Chat, Chat/Embedding, or
Embedding/Embedding native execution.

An active task is never preempted by a newer task or a different requested model. A cancelled waiter
is removed from admission and cannot disturb the relative order of remaining waiters. Task failure or
cancellation still releases its queue ownership and participates in the same final idle transition.

Stop targets only the currently active Chat engine through its thread-safe native cancellation path.
It does not cancel Embedding work, unload a model directly, cancel waiting Local tasks, or acquire the
permit held by the active native generation.

## 5. Idle offload lifecycle

Idle means there are no queued or active Local tasks. A model may remain resident while idle for the
configured retention duration.

1. Arrival of any Local task invalidates and cancels the current idle deadline before admission.
2. A deadline starts only when the final queued-or-active task has completely unwound.
3. Expiry re-enters the same FIFO permit as conditional maintenance and unloads only after proving
   that the queue is still empty for the captured idle epoch.
4. A task arriving at the expiry boundary linearizes either before unload, cancelling or invalidating
   it, or after the completed unload and then loads its requested identity normally.
5. A setting change while idle invalidates the old epoch and restarts the deadline from the change.
   A setting change during work affects the deadline created after the final task completes.
6. Zero minutes unloads immediately after the final queued-or-active task completes. It never unloads
   between already submitted tasks.

Only the configured duration persists. An in-flight deadline or remaining elapsed time is not
restored after process death; the new process begins with no resident model and no inherited timer.

## 6. Setting and UI contract

`local_model_idle_retention_minutes` accepts only `0, 1, 2, 5, 10, 15, 30`; invalid or absent values
normalize to the five-minute default. It is stored in this device's DataStore and exposed at
Provider -> Local -> Advanced as the discrete `Model idle retention` slider. Zero is presented as
immediate offload; positive presets are presented in minutes.

The default resource and every supported locale define the same localized key and placeholder set.
The setting is device-local: it is excluded from portable Settings export/import and survives a
Settings `REPLACE`, as specified by [import-export.md](import-export.md).

`local_low_context_mode_enabled` is a separate device-local Boolean and defaults to `false`. It is
exposed at Provider -> Local -> Advanced as the default for new and existing conversations that have
no explicit override. A conversation or New Chat stores a nullable override: `null` inherits the
current Provider default, while an explicit `true` or `false` continues to win if the default later
changes. The request effect remains limited to ordinary embedded Local Chat as specified by
[message-generation.md](message-generation.md); it does not change model residency, native context
identity, Compact/title prompts, Ollama, or remote Providers.

## 7. Native streaming and telemetry

Native text and multimodal decoding check cancellation after every decoded token. Complete UTF-8 is
delivered through the blocking callback after at most four decoded tokens or 64 complete bytes,
whichever occurs first. Callback rejection stops generation without retrying rejected bytes. Before
any other terminal result, all already-decoded complete bytes are delivered; an incomplete final
UTF-8 sequence is an explicit failure rather than replacement or truncation.

Debug telemetry may record model/context setup time, prefill/decode/request duration, image count,
token counts, terminal category, and tokens per second. It must never record prompts, generated text,
message content, model paths, image paths, or other private payloads.

## 8. Prohibited behavior

Never introduce a second Local lock, model cache, lifecycle manager, offload timer, Provider-local
fallback, per-caller unload callback, or identity definition. Never unload directly from a timer
without reacquiring the canonical permit and revalidating the idle epoch. Do not restore idle
deadlines across processes, interrupt active native work to honor a deadline, or export this setting.
Never invent a fallback chat template or bypass the official model-owned Jinja path.

## 9. Required verification

Focused verification must cover FIFO ordering, no native overlap, cancelled-waiter removal,
Chat/Embedding/path/context identity changes, unload-before-load, failed replacement, same-identity
reuse, active-Chat-only Stop, and Embedding input isolation. Idle tests must cover arrival
cancellation, no countdown while queued/active, last-task deadline start, setting-change restart,
zero-minute behavior, expiry-versus-arrival linearization, and unload through the same permit.

Chat-template verification must cover explicit-template enforcement, official Jinja ownership,
request-level thinking control, UTF-8-safe prompt transfer, and absence of generic fallbacks.
Native-streaming verification must cover both generation loops, exact batch bounds, UTF-8 boundary
safety, terminal flushing, callback rejection, per-token cancellation, and content-free telemetry.
Java-to-native Chat and Embedding strings use standard UTF-8 with explicit byte lengths, including
supplementary Unicode and embedded NUL in text. Filesystem paths reject NUL instead of truncating.
Run the host test with "python scripts/test-native-utf8.py" and a JDK/C++ compiler when this boundary
changes; the actual production JNI helper is compared with Java's UTF-8 encoder under JNI checks.
Text-cache verification must cover same-identity reuse, token LCP divergence, exact-match one-token
replay, prompt-capacity validation before mutation, failed truncation, decode failure, cancellation
between successful batches, generated-token ledger ordering, and multimodal invalidation. Android
backend verification must cover shared dynamic builds, all seven upstream Android ARM CPU modules,
armv8.0 baseline packaging, explicit loading from `applicationInfo.nativeLibraryDir`, initialization
before either model-load path, and absence of KleidiAI, Vulkan, and OpenMP.

Settings tests must cover the exact presets/default/normalization, DataStore read/write, one
AppContainer binding, Local Advanced placement and slider commit behavior, locale key/placeholder
parity, portable-export absence, and Settings Replace preservation. The project full build remains
required; build success alone does not prove real-device memory release or model reload latency.

## 10. Native module boundaries

`llama_chat_jni.cpp` owns the existing model lifetime, cancellation entry, text generation and
multimodal generation entry points. `llama_chat_handle.h` declares their shared per-model state;
moving that declaration does not create another runtime owner. Template request/result conversion
and get/apply entry points live in `llama_chat_template.cpp`.

Both generation paths use `llama_chat_generation.cpp` for token conversion, proven-prefix cache
operations and sampler setup. `llama_chat_parser.h` retains per-request typed stream parsing;
`llama_chat_callbacks.cpp` retains JNI output conversion and callback dispatch. These modules add
no global model state, queue, timer or lifecycle. Keep their batching, cancellation and failure
ordering equivalent when changing module boundaries.
