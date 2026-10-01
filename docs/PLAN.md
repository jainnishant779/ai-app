# Nishu Android V0.1 — Conversation Recorder

> **Companion docs:** [CONTRACT.md](CONTRACT.md) — byte-level facts, read before writing any prompt/tokenizer/engine code · [DECISIONS.md](DECISIONS.md) — what was decided and why · [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md) — what's still unknown and how to measure it

## Context

`D:\nishant\llm_research` is the **model/data side** of this project: a fine-tune pipeline for **Nishu**, a Hinglish phone assistant built on Qwen3-0.6B (GGUF Q4_K_M, `n_ctx` 1024, thinking off — tested so far with llama.cpp, which is the training-side test harness, not an app-side commitment). It contains 6,212 training conversations, a training notebook, and ~20 research notes. It contains **zero Android files** — verified by sweeping for `*.kt`, `*.gradle`, `AndroidManifest.xml`, `*.java`.

This plan builds the Android app, which does not exist yet. Two facts from the repo shape it:

1. **There is no trained Nishu model.** `HANDOFF.md:24` — *"Asli GPU training abhi nahi hui."* The only usable GGUF is stock, untuned `llama_bin\qwen3-0.6b-Q4_K_M.gguf`. (`model-Q4_K_M.gguf` at the repo root is the *failed* SmolLM2 merge — wrong architecture and tokenizer. Do not use it.)
2. **The prompt is a byte-level contract.** `HANDOFF.md:31` — *"Phone app ko bilkul yahi prompt bhejna hai."* The training data baked an exact system prefix into every example, so the app must reproduce it byte-for-byte or tool calling and Hinglish quality degrade in ways that look like model bugs.

**Outcome:** a working Android app that records a conversation, transcribes it on-device, and produces a summary plus tasks and decisions — with the prompt contract proven by test, so dropping in `nishu-Q4_K_M.gguf` later is a file replacement.

### Decisions already made (do not re-litigate)

| Decision | Source |
|---|---|
| Native Kotlin + Jetpack Compose, minSdk 26 | user |
| Product = conversation recorder (Home / Conversation / Memory) | user |
| Build against stock Qwen3-0.6B now; swap in Nishu later | user |
| Chunked map-reduce for long recordings | user |
| SQLite via Room; sherpa-onnx for STT | user |
| **Runtime is NOT decided.** llama.cpp is V0.1's first engine only; ExecuTorch/MLC benchmarked after the fine-tune exists | user |
| Target hardware: **Snapdragon** (Qualcomm QNN and Adreno OpenCL both viable later) | user |
| Full fine-tune, LR 3e-5, loss on assistant turns only | `HANDOFF.md` "Pakke faisle" |
| q8_0/q8_0 KV (**q4_0 on K forbidden** — collapses tool calling) | `reports\On device phone action tools.md:35` |
| Greedy + GBNF once `<tool_call>` appears; Qwen3 chat defaults otherwise | same, :78 |
| Prefix KV cache built **on-device**, never shipped in the APK | same, :31 |

Electron was considered and ruled out: it has no Android target.

### Runtime choice stays open

llama.cpp is **not** assumed optimal. It is V0.1's first engine because GGUF export already works and the model/quant are settled — not because it wins. Its real ceiling is concrete: Adreno OpenCL supports only Q4_0/Q6_K, so **Q4_K_M cannot use the GPU at all**, leaving the Snapdragon GPU and NPU idle.

ExecuTorch is the serious challenger (XNNPACK on CPU, Vulkan on GPU, **Qualcomm QNN on NPU**), with MLC behind it. The benchmark happens, but **after** the fine-tune exists, for a reason: the two metrics that decide this — Hinglish quality and JSON reliability — cannot be measured on a stock untuned model. Benchmarking three runtimes today would measure tok/s only, while costing three separate export pipelines (GGUF, `.pte` + QNN lowering, MLC/TVM compile) and three sampler implementations. Given that the previous attempt already failed on garbled output, that is risk multiplied for no answer.

So V0.1 does two things to keep the door open: it puts the real contract in the `LLMEngine` seam (below), and it ships a **`BenchmarkScreen`** that records TTFT, tok/s, peak RSS, CPU/battery, SoC name, and output quality per engine — so `ExecuTorchEngine` plugs into an existing harness rather than a rewrite.

The rule, stated once: **`V0.1 first engine = llama.cpp`; the runtime winner is decided by `BenchmarkScreen` after the fine-tune exists.** Where this plan says "llama.cpp", read it as "V0.1's first engine".

| Component | V0.1 |
|---|---|
| Android | Kotlin + Compose, minSdk 26 |
| STT | sherpa-onnx (whisper-tiny.en int8 + Silero VAD) |
| Model | Qwen3-0.6B — stock/untuned today, Nishu after training |
| Runtime | llama.cpp — **first engine, not the winner** |
| Format / execution | GGUF Q4_K_M, CPU (engine limitation, not app limitation) |
| Database | Room / SQLite + FTS4 |
| Prompt | byte-exact Qwen3 template, hash-asserted |
| Tool grammar | GBNF (llama.cpp only; others fall back to greedy + salvage) |
| KV cache | on-device prefix state file |
| Runtime alternatives | **not implemented** — harness built, engines land post-training |

Everything above the engine boundary — Room, STT, summarization, UI, workers — is unaffected by the eventual runtime choice. That isolation is the point.

### The scope conflict, stated plainly

The repo's research specifies a **phone-action assistant** (alarms, calls, torch, apps). You chose the **conversation recorder**. These are different products sharing one model. Consequences:

- The model's 9 tools (`set_alarm`, `make_call`, …) are **not wired to Android APIs** in V0.1. The tool-call parser and GBNF grammar are still built — they prove the contract and drive JSON extraction.
- **Tasks/decisions extraction is outside the model's trained distribution.** Of 490 summarization examples, all are short-passage bullet summaries; **none** extract JSON from a transcript. With today's untuned model, output will be poor. The plan handles this with a 3-tier extraction ladder and an honesty column in the schema, rather than pretending otherwise.
- A 30-minute recording cannot fit in 1024 tokens. Hence map-reduce.

---

## Verified ground truth

Summary only — the full contract, including tool schemas and behavioral invariants, is in **[CONTRACT.md](CONTRACT.md)**. Measured directly, not assumed:

```
canonical system prompt  = train.jsonl line 1, messages[0].content
  1024 chars / 1024 UTF-8 bytes, 11 LF, 0 CR
  sha256 0d30a62b8de680791d8acbc8a14e5d62df0d0cca2c4474a3ae63a98aead6f3c3

llama_bin\sys_test.txt   = 1035 bytes, 11 CR + 11 LF
  sha256 030a036c...  ->  NOT the training prompt. Never use as the app asset.
```

**The prompt asset must be extracted from `train.jsonl`, with LF endings, hash-asserted.** The CRLF copy would shift tokenization at all 11 line boundaries.

Other contract facts (from `dataset_builder.py:95-112`, `:955-957`, and the data):

- System prefix ships on **every** request, including plain chat — training rendered it everywhere, so chat teaches "tool list present, no tool needed."
- `enable_thinking=false` does **not** remove the think block. The prompt must end literally with `<|im_start|>assistant\n<think>\n\n</think>\n\n`.
- Tool calls: `<tool_call>\n{"name": ..., "arguments": {...}}\n</tool_call>`. Keys are `name`/`arguments`, **not** `function`/`parameters`. `arguments` is a real JSON object on the wire. Exactly **one call per turn** across all 6,212 rows.
- Role `tool` renders as **`<|im_start|>user` wrapping `<tool_response>`**, not `<|im_start|>tool`. Easiest thing here to get silently wrong.
- `addSpecial` must be **false** on every eval — `<|im_start|>` is already in the text; an added BOS shifts every index and invalidates the prefix cache.
- **The 294-token prefix figure is unverified.** Measure it with `llama_tokenize` at first launch and store it. Do not hardcode it; chunk sizes depend on it.

---

## Project layout

The app lives in this repo (`D:\nishant\ai-app\`) as the `app/` module next to `docs/`. The model repo `D:\nishant\llm_research` stays untouched; it is read as the source of truth for fixtures.

```
D:\nishant\ai-app\
├── .gitattributes                  # app/src/main/assets/** -text -diff
├── third_party\llama.cpp\          # git submodule, PINNED tag
├── tools\
│   ├── extract_system_prompt.py    # train.jsonl -> system_prompt.bin (LF), asserts sha256
│   └── render_golden_prompts.py    # transformers template -> golden fixtures
└── app\src\
    ├── main\
    │   ├── cpp\      llama_jni.cpp, nishu_session.cpp
    │   ├── assets\   system_prompt.bin, grammars\extract.gbnf
    │   └── java\com\nishu\app\
    │       ├── llm\        LLMEngine.kt, PromptTemplate.kt, Qwen3PromptTemplate.kt,
    │       │   │            ToolCallParser.kt, SamplerProfile.kt, Benchmark.kt
    │       │   └── llamacpp\  LlamaCppEngine.kt, LlamaBridge.kt, PrefixCache.kt
    │       │       (later: executorch\ExecuTorchEngine.kt, mlc\MLCEngine.kt)
    │       ├── audio\      RecorderService.kt, PcmWriter.kt
    │       ├── stt\        SttEngine.kt, SherpaOnnxStt.kt
    │       ├── data\       NishuDatabase.kt, entity\, dao\
    │       ├── work\       TranscribeWorker.kt, SummarizeWorker.kt
    │       ├── summarize\  Chunker.kt, MapReduceSummarizer.kt, JsonSalvage.kt
    │       └── ui\         home\, conversation\, memory\
    └── androidTest\...\PromptBytesTest.kt      # the contract test
```

Single `:app` module. Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Room 2.6.1 (KSP), WorkManager 2.9.1, NDK r27c, CMake 3.22.1. `compileSdk` 35, `targetSdk` 34, `minSdk` 26.

**`abiFilters = ["arm64-v8a"]` only.** CMake flags: `-DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_{TESTS,EXAMPLES,TOOLS,SERVER}=OFF`. `noCompress += ["bin","gbnf","onnx","gguf"]` so `AssetManager` returns exact bytes.

**CPU inference only *for the llama.cpp engine*** — the Adreno OpenCL backend supports just Q4_0/Q6_K, so Q4_K_M cannot use it. This is a property of this engine, not of the app: GPU/NPU execution is exactly what `ExecuTorchEngine` (QNN) is meant to unlock later, which is why `BenchmarkScreen` records the SoC and thermal data from V0.1 onward.

---

## Implementation steps

Ordered so the byte-level contract is proven **before** product code depends on it, and so the memory gate is checked early rather than discovered at the end.

### 1–2. Skeleton + llama.cpp builds
Compose app, arm64-v8a. Add llama.cpp as a **git submodule pinned to a tag**; `add_subdirectory` from `app/CMakeLists.txt`; link `llama ggml android log`. The pinned tag feeds the KV cache key — bumping llama.cpp invalidates caches by design.

**Verify:** `libnishu_llama.so` present in the APK; app logs the llama.cpp build commit.

### 3. Model loads — check the memory gate now
Side-load the GGUF to `filesDir/models/llm/` via `adb push`. Call `loadModel(path, nCtx=1024, nThreads=4, "q8_0", "q8_0")`.

**Verify:** handle != 0, and `adb shell dumpsys meminfo com.nishu.app` TOTAL PSS **< 500 MB** (the release gate from `reports:133`). If it fails here, reduce `n_ctx` before building anything else.

### 4. Prompt bytes match training — the critical step
1. `tools/extract_system_prompt.py` reads `D:\nishant\llm_research\data\v2\final\train.jsonl` line 1, writes `assets/system_prompt.bin` with LF endings, and **asserts length 1024 + sha256 `0d30a62b…`**. It must not read `sys_test.txt`.
2. `tools/render_golden_prompts.py` loads the Qwen3-0.6B tokenizer in `transformers` and calls `apply_chat_template(..., add_generation_prompt=True, enable_thinking=False)` on ~8 rows covering: plain chat, summarization, a full `system,user,assistant,tool,assistant` tool row, multi-turn `phone_followup`, and identity. Emits `.prompt.bin` + `.tokens.json` fixtures.
3. `PromptBytesTest` (instrumented, on-device) asserts **byte equality** for each, and separately asserts `LlamaBridge.tokenize()` IDs equal the golden token IDs — byte equality alone can hide an `addSpecial` mistake.
4. Log the real `systemPrefixTokenCount` and record it.

**Verify:** `gradlew connectedDebugAndroidTest` green.

### 5. Generation works mechanically
Greedy, 128 tokens, on a known test row. With the untuned model the *content* will be garbage — expected. Pass criteria are mechanical: valid UTF-8 (proves the partial-UTF8 accumulator), stops at `<|im_end|>`, `FinishReason == STOP`, and a logged tok/s (target ≥8) that sets every later timeout.

### 6. Tool-call parser + grammar switch
`ToolCallParser` unit-tested on every `tool_calls` row in `train.jsonl`. On-device, verify grammar-constrained generation — **including the mid-generation grammar sampler reset**, without which output is silently empty.

### 7. Prefix KV cache
`warmPrefix()`: on miss, eval `renderPrefix()` and `saveState`; on hit, `loadState`. Key = model sha256 + llama.cpp build + KV types + `n_ctx` + prefix sha256.

**Verify:** warm load much faster than cold prefill; bumping `n_ctx` forces a rebuild rather than a stale load; `renderPrefix()` hashes identically across two launches (catches anything dynamic leaking into the cached prefix).

### 8. Audio capture
`AudioRecord(VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)` — 16 kHz mono PCM16 matches sherpa-onnx natively, so **no resampling anywhere**. Foreground service, `foregroundServiceType="microphone"`, `startForeground()` before the first `read()`, plus a `PARTIAL_WAKE_LOCK`. Append raw PCM live and patch the WAV header on stop, so process death loses nothing. ~115 MB/hour — surface this, and offer "delete audio, keep transcript."

**Verify:** a 60 s WAV plays at correct pitch on desktop (wrong pitch = sample-rate bug); then a **10-minute screen-off recording** with no truncation, on the real target phone (catches OEM background killers early).

### 9. sherpa-onnx transcription
Maven `com.k2-fsa.sherpa.onnx:sherpa-onnx`. **`OfflineRecognizer`**, not streaming — transcription happens after stop, and batching VAD segments is more accurate. **Silero VAD is required, not optional:** whisper hallucinates confidently on silence, and VAD segments give real `TranscriptSegment` timestamps for free.

**Model choice, stated honestly:** no production Roman-script Hinglish ASR model exists. Hindi models emit Devanagari, which `dataset_builder.py`'s `is_hinglish` rejects. V0.1 ships `whisper-tiny.en` int8 (~40 MB) and documents Hinglish ASR as a known limitation. `SttEngine` is an interface, so upgrading is a config change.

Models live in `filesDir/models/`, side-loaded via `adb push` — bundling 40 MB STT + 378 MB GGUF would make an unshippable ~420 MB APK.

**Verify:** monotonic non-overlapping segments covering the audio; **zero segments from a 10 s silent recording**.

### 10. Room schema
Entities: `ConversationEntity` (status: RECORDING → … → DONE/FAILED, `llmModelId`), `TranscriptSegmentEntity` (`startMs`/`endMs`, `speakerLabel` column reserved), `SummaryEntity`, `TaskEntity`, `DecisionEntity`. CASCADE deletes; `@Fts4` over transcript segments for Memory search; `exportSchema=true` with `schemas/1.json` committed.

Two deliberate choices:
- **`extractionConfidence` on every task/decision** (`MODEL_JSON` / `MODEL_SALVAGED` / `HEURISTIC`). This is what lets the UI say "low confidence" instead of lying — and it is what makes a stock-model V0.1 honest rather than broken.
- **`dueHint` stays a String.** Never let a 0.6B model's date guess become a timestamp. The research is explicit that date arithmetic belongs in Kotlin (`reports:60-62`).
- `bulletsText` is the primary summary, not JSON — bullets are what the 490 training examples actually teach.

### 11. Chunked map-reduce summarization
Budget: 1024 − measured prefix (~294) − instruction wrapper (~60) − 160 generation reserve ⇒ **chunk target 420 tokens, hard cap 480**. `Chunker` packs whole segments using `engine.tokenCount()` (the real tokenizer, not chars/4 — Roman-script Hindi tokenizes ~1.5× worse than English), with ~20% overlap so a decision spanning a boundary survives.

Map and reduce prompts deliberately mirror the trained phrasing (`Summarize ... in 3 bullet points:\n\n<text>`), which is the highest-leverage choice in this step. Hierarchical tree-reduce if the concatenation overflows — never truncate.

**Tasks/decisions — 3-tier ladder, first success wins:**
1. **GBNF-constrained JSON, greedy**, run on the *reduce bullets* (shorter, and already in-distribution output the model produced itself). Grammar guarantees syntax, not semantics — that's the right trade, since syntax failure is the part we can eliminate for free.
2. **`JsonSalvage`** — repair truncated JSON by depth tracking. Mark `MODEL_SALVAGED`.
3. **Heuristic regex, no model** (`karna hai`, `TODO`, `decided`, `tay hua`, …). Mark `HEURISTIC`. **This tier is what makes V0.1 demoable on today's untuned model.**

Degradation rules: empty map → retry greedy once, then skip and count; repetition loop → detect with the same rule `dataset_builder.py` uses and truncate; all maps fail → `FAILED`, UI shows transcript + "Summary unavailable"; nothing found → empty lists and "No action items found", **not** an error. The transcript alone is a useful product; summary/tasks/decisions are additive.

**Runs in WorkManager**, two chained workers (`TranscribeWorker` → `SummarizeWorker`), both `CoroutineWorker` with `setForeground()` and progress. A **process-wide `Mutex` around `LLMEngine`**, which is a lazy singleton that unloads after 60 s idle.

**The RAM rule that makes this work:** `TranscribeWorker` must unload the ONNX STT model **before** `SummarizeWorker` loads the LLM. Holding onnxruntime and a ~460 MB llama context simultaneously will exceed the 500 MB gate. The chaining exists to make that enforceable — verify with `dumpsys meminfo` at the worker boundary.

**Verify:** on a ≥15-minute transcript, every chunk ≤480 tokens and zero `CONTEXT_FULL`. Then force each degradation path with a stubbed engine. Check wall time: at 8 tok/s and ~160 gen tokens per map step, 20 chunks ≈ 7 minutes — if too slow, cap V0.1 at ~12 chunks (~25 min audio) and say so in the UI.

### 12. Compose UI
Routes `home`, `conversation/{id}`, `memory`. Home: record FAB bound to service state, live timer, recent list from a Room `Flow` with status chips — **no polling**. Conversation: audio player where tapping a segment seeks to `startMs`; Summary / Tasks (checkboxes writing back) / Decisions / Transcript, with low-confidence badges. Memory: FTS search (250 ms debounce) + single-shot "Ask" (top-3 segments, capped ~400 tokens).

All state in `ViewModel` + `StateFlow`, `collectAsStateWithLifecycle()`; inference never tied to composition, so rotation mid-generation doesn't restart it. Streaming throttled to ~30 ms to avoid jank. A visible Cancel on every long operation.

### 13. Model swap rehearsal
Replace the GGUF with a byte-different copy. The app should detect the new sha256, discard the stale prefix state, rebuild it, and **step 4's prompt test must still pass with zero code changes.** This proves the seam.

### 14. `BenchmarkScreen` — the runtime harness
A debug-only screen that runs a fixed suite over the current engine and writes one CSV row per run to `filesDir/benchmarks/`. Metrics, matching what actually decides the runtime question:

| Metric | How |
|---|---|
| TTFT | first `onPiece` minus submit |
| tok/s decode | gen tokens / wall time |
| Prefill tok/s | prefix tokens / cold prefill ms |
| Peak RSS | `rssBytes()` sampled during generation |
| Model size on disk | file length |
| CPU / battery | `BatteryManager` delta over a fixed 20-prompt run |
| Thermal | `PowerManager.currentThermalStatus` sampled |
| SoC | `Build.SOC_MODEL` (API 31+), else `Build.HARDWARE` |
| JSON reliability | % of extract runs yielding valid JSON at tier 1 |
| Hinglish quality | side-by-side output dumped to CSV for manual rating |

The last two are **the deciding metrics and they are meaningless until the fine-tune exists** — on stock Qwen3-0.6B they measure nothing. Run the suite for real after training; until then it establishes the speed/memory baseline and proves the harness works.

**The fixed suite** must come from held-out data, not ad-hoc prompts: ~20 rows sampled from `D:\nishant\llm_research\data\v2\final\test.jsonl` spanning `hinglish_qa`, `english_chat`, `summarization`, `tool_call`, and `decline`, plus one long map-reduce run. Same rows for every engine, or the comparison means nothing.

### Later: `ExecuTorchEngine` (post-training, not V0.1)
Sequenced after `nishu-Q4_K_M.gguf` is trained and validated. Work involved, so the cost is visible: export the fine-tuned checkpoint to `.pte`, lower for XNNPACK (CPU baseline) and then **Qualcomm QNN** (the actual prize on your Snapdragon), implement `PromptTemplate` rendering against ExecuTorch's tokenizer, and pass step 4's golden byte/token test before any number it produces is believed. Expect the QNN path to constrain quantization — NPU backends typically want their own scheme rather than Q4_K_M, which is itself part of what the benchmark should reveal.

---

## The `LLMEngine` seam

Abstracting the runtime is right. But the obvious form of it is a trap:

```kotlin
// DO NOT: this is the wrong seam
interface LLMEngine { suspend fun generate(prompt: String, context: String = ""): String }
```

`prompt: String` assumes the prompt is runtime-independent. It is not. This model is fused to a 1024-byte system prefix, an empty `<think>\n\n</think>` block, a `tool` role that renders as `<|im_start|>user` + `<tool_response>`, and `addSpecial=false`. llama.cpp, ExecuTorch and MLC each tokenize and apply templates differently. If each engine builds its own prompt behind `generate(prompt)`, they send **different bytes** to the same weights — and the benchmark then reports "ExecuTorch is worse at Hinglish" when the actual cause is a template mismatch. The comparison would be invalid in a way that is nearly invisible.

So the seam carries the contract explicitly, which is also what makes the benchmark trustworthy: every engine renders through the *same* `PromptTemplate` and is verified against the *same* golden byte fixtures (step 4).

```kotlin
interface LLMEngine : AutoCloseable {
    val template: PromptTemplate          // byte-exact prefix + think block + tool_response quirk
    val parser: ToolCallParser
    val prefixCacheKey: PrefixCacheKey    // model sha + llama build + KV types + nCtx + prefix sha
    val contextTokens: Int
    val turnTokenBudget: Int              // ctx minus MEASURED prefix

    suspend fun warmPrefix(): Boolean
    suspend fun generate(messages: List<ChatMessage>, sampler: SamplerProfile,
                         maxTokens: Int, stopSequences: List<String> = listOf("<|im_end|>"),
                         onPiece: ((String) -> Unit)? = null): GenerationResult
    suspend fun generateWithToolSwitch(messages: List<ChatMessage>,
                                       chat: SamplerProfile.Chat,
                                       toolGrammar: SamplerProfile.Grammar,
                                       maxTokens: Int,
                                       onPiece: ((String) -> Unit)? = null): GenerationResult
    fun tokenCount(text: String): Int
    fun cancel()
    fun rssBytes(): Long                  // the 500MB gate must be test-observable
}

sealed interface SamplerProfile {
    data class Chat(val temp: Float = 0.7f, val topP: Float = 0.8f,
                    val topK: Int = 20, val seed: Int = -1) : SamplerProfile
    data object Greedy : SamplerProfile
    data class Grammar(val gbnf: String, val rootRule: String = "root") : SamplerProfile
}
```

`LlamaCppEngine` is the only implementation in V0.1. `ExecuTorchEngine` and `MLCEngine` implement the same interface later. Note two things the interface deliberately does **not** assume: `prefixCacheKey` is llama.cpp-specific state-file caching, so other engines return their own equivalent or a no-op from `warmPrefix()`; and `SamplerProfile.Grammar` is GBNF, which ExecuTorch/MLC do not support natively — those engines fall back to `Greedy` plus the `JsonSalvage` tier, which is itself a benchmark finding worth recording.

**On the 135M/360M/0.6B/1.7B benchmark idea:** keep the interface, but note the repo has already settled the model question. SmolLM2-360M's garbling was diagnosed as *"a base-model capability ceiling, not a pipeline bug"* — it is English-only by construction (`reports\Hinglish model choice and garbled output.md:3,19`), and the stated fallback is *"shrinking the quant, not returning to SmolLM2."* A 1.7B is ~1.1–1.4 GB at Q4_K_M against a ~500 MB budget. So the seam is for swapping **quants and Nishu revisions**, not for a size sweep.

### Prompt builder: hand-roll it
**Do not use `llama_chat_apply_template`.** `enable_thinking` is a *template kwarg* with no clean path through llama.cpp's C API (`HANDOFF.md:32` shows it only as a CLI flag), so the empty-think block can't be guaranteed. A hand-rolled builder is testable against `transformers`-rendered golden fixtures and gives the prefix/turns split that prefix caching needs.

### C++ worth naming
`nishu_session.cpp` must handle: a **partial-UTF8 accumulator** (`llama_token_to_piece` can return half a codepoint — this is the #1 cause of "garbled Hinglish" that looks like a model bug, and this team already lost time to garbled output once); batch-safe prefill over `n_batch`; dual sampler chains with the **grammar sampler first**; grammar reset on mid-generation switch; state save/load; cooperative cancellation; `llama_backend_init` once in `JNI_OnLoad`.

---

## Risks

Each of these is tracked with a measurement procedure in **[OPEN-QUESTIONS.md](OPEN-QUESTIONS.md)**.

1. **The 294-token prefix is unverified.** If it's ~340, chunk sizes must shrink. Measured at step 4, before step 11 depends on it.
2. **500 MB RSS is tight** — 397 MB weights + ~60 MB KV + ART/Compose/Room/ONNX. Mitigated by never holding STT and LLM together.
3. **Tasks/decisions is out of distribution.** On the untuned model, the heuristic tier will likely beat the model. `extractionConfidence` keeps that honest.
4. **Hinglish ASR barely exists.** A documented V0.1 limitation, not a solvable problem.
5. **OEM background killers** (Xiaomi/Oppo/Vivo) will kill long foreground services — caught at step 8.
6. **Silent failure modes:** steps 4 and 7. Both degrade output in ways that look like "the model is bad." The golden-fixture test asserting **both bytes and token IDs** is the most valuable code in this plan.

7. **Runtime lock-in** is the risk you flagged, and it is real: llama.cpp cannot use the Snapdragon GPU or NPU with Q4_K_M. Mitigated structurally rather than by building three runtimes now — the seam carries the full contract, and `BenchmarkScreen` exists from V0.1 so the comparison is a plug-in, not a rewrite. The counter-risk is spending V0.1 on three export pipelines and shipping nothing measurable.

## Cut from V0.1

Model downloader (side-load via adb) · speaker diarization (column reserved) · real-time transcription during recording · **wiring the 9 phone-action tools to Android APIs** (parser and grammar still built) · multi-turn Memory "Ask" · due-date parsing · multi-module Gradle · non-arm64 ABIs · **`ExecuTorchEngine` / `MLCEngine`** (step 14 builds the harness they plug into; they land after the fine-tune exists).

## Verification summary

Each step above is independently provable on a real device. The three gates that matter most:

- **Step 3:** PSS < 500 MB with the model loaded.
- **Step 4:** `connectedDebugAndroidTest` green — prompt bytes *and* token IDs match `transformers` ground truth.
- **Step 13:** a model swap changes nothing but the file and the hash.
