# Decisions

What was decided, why, and where the evidence lives — so nobody re-argues a settled call, and so anyone reversing one knows what they're overturning.

Three sources of authority, in descending order:
1. **User** — the product owner's call. Not overridable by research.
2. **`HANDOFF.md` "Pakke faisle"** — *"inhe dobara mat chhedna, jab tak koi naya sabut na mile"* ("don't touch these again unless new evidence appears"). Training-side.
3. **`reports/`** — synthesized research. Where two reports conflict, the later one wins and says so.

---

## Product and platform

### Native Kotlin + Jetpack Compose, minSdk 26
**Who:** user. **Status:** settled.

Also considered:
- **Electron** — ruled out on a fact, not a preference: it has no Android target. Desktop only, no phone mic, no Android Intents, no Play Store. Would be viable only as a throwaway desktop prototype (the Windows llama.cpp binaries in `llama_bin/extracted/` make that cheap), but no code ports to the phone.
- **React Native + llama.rn** — viable, and `llama.rn` already wraps `saveSession`/`loadSession` and grammars. Rejected because the user wants Kotlin, and the executor work is native anyway.

The repo weakly backs Kotlin already: `reports/On device phone action tools.md:58` is literally the section heading *"The model maps words to slots; Kotlin does the arithmetic"*, and `:86` expects the eval harness to call *"the real Kotlin … resolver"* the app ships. It was never formally decided, just assumed.

> **Note on `llama.rn`:** it appears 5 times in the research, always as a *citation* proving the llama.cpp KV-state API is reachable on Android — never as a framework recommendation. `reports:31` says *"llama.rn exposes **the same thing** on Android"*, with `llama.h` cited first. Do not read those mentions as a React Native decision.

### Product = conversation recorder, not phone-action assistant
**Who:** user. **Status:** settled, with known consequences.

The repo's research specifies a **phone-action assistant** (alarms, calls, torch, apps). The user chose the **conversation recorder** (record → transcribe → summary/tasks/decisions). Same model, different product. Accepted consequences:

- The 9 tools are **not wired to Android APIs** in V0.1. Parser + GBNF grammar still built — they prove the contract and drive JSON extraction.
- **Tasks/decisions extraction is out of the model's trained distribution.** All 490 summarization examples are short-passage bullet summaries (`dataset_builder.py:843-862`); **zero** extract JSON from a transcript. Handled with a 3-tier fallback ladder and an `extractionConfidence` column rather than pretending the output is trustworthy.
- `reports/On device phone action tools.md` remains the best Android reference in the repo even though its *product* differs — its permission matrix, memory budget, KV-quant and sampler findings all still apply.

### Target hardware: Snapdragon, arm64-v8a only
**Who:** user. **Status:** settled.

Keeps both acceleration paths open later: Qualcomm QNN (ExecuTorch) and Adreno OpenCL (llama.cpp, though see the Q4_K_M limitation below). Only ABI shipped is arm64-v8a — llama.cpp's Android support is arm64-v8a/x86_64, and x86_64 is emulator-only.

---

## The model

### Qwen3-0.6B, full fine-tune, GGUF Q4_K_M
**Who:** `HANDOFF.md` "Pakke faisle". **Status:** settled. Do not reopen.

Full fine-tuning, **not LoRA** — removes the merge step that produced garbled output on the first attempt. LR 3e-5, 3 epochs, effective batch 32, cosine, fp32 weights. Loss on assistant turns only. If the model doesn't learn, try LR 5e-5.

### V0.1 builds against the stock untuned model
**Who:** user. **Status:** settled.

`HANDOFF.md:24` — *"Asli GPU training abhi nahi hui"* (GPU training hasn't happened). Only `llama_bin\qwen3-0.6b-Q4_K_M.gguf` (stock, 378 MB) is usable.

So V0.1's pass criteria are **mechanical, not quality-based**: valid UTF-8, correct stop token, prompt bytes match, memory under budget. Tool and Hinglish accuracy will be poor — expected, not a bug. Swapping in `nishu-Q4_K_M.gguf` later is a file replacement plus a new hash.

> `model-Q4_K_M.gguf` at the research repo root is the **failed SmolLM2 merge** — `general.architecture=llama`, `size_label=362M`, `tokenizer.ggml.pre=smollm`. Wrong architecture, wrong tokenizer. Never use it.

### No model-size sweep (135M / 360M / 1.7B)
**Status:** the size question is already settled; keep the interface, drop the sweep.

- **SmolLM2-360M is affirmatively rejected**, not merely unpreferred. The garbling was diagnosed as *"a base-model capability ceiling, not a pipeline bug"* because *"SmolLM2 is English-only by construction"* (`reports/Hinglish model choice and garbled output.md:3`). And `:19`: the ~130 MB saved *"buys you a model that **cannot do the job**"*. Stated fallback is *"shrinking the quant, not returning to SmolLM2."*
- **1.7B busts the budget.** Llama 3.2 **1B** is already 808 MB at Q4_K_M against a ~500 MB soft budget; a 1.7B is ~1.1–1.4 GB. Different device class.
- The model is also **fused to its prompt**: the tool list is baked into every training example, and `reports:90` notes *"switching later means regenerating the set."*

So the `LLMEngine` seam is for swapping **runtimes, quants and Nishu revisions** — not model sizes.

---

## Runtime

### llama.cpp is V0.1's first engine — NOT the final choice
**Who:** user. **Status:** deliberately open.

The rule: **`V0.1 first engine = llama.cpp`; the runtime winner is decided by `BenchmarkScreen` after the fine-tune exists.**

llama.cpp goes first because GGUF export already works and the quant is settled — not because it wins. Its ceiling is concrete: **Adreno OpenCL supports only Q4_0/Q6_K**, so Q4_K_M cannot touch the GPU, leaving the Snapdragon GPU and NPU idle.

ExecuTorch is the serious challenger (XNNPACK CPU, Vulkan GPU, **Qualcomm QNN NPU**), with MLC behind it. The research never decided this — it only compared runtimes once, for an *embedded Linux* project (`research_notes/Tool calling failures in small LLMs/architecture_patterns.md:90`), where llama.cpp beat MLC/ExecuTorch. Not an Android finding.

**Why benchmark later, not now:** the two metrics that decide it — Hinglish quality and JSON reliability — **cannot be measured on an untuned model**. Benchmarking three runtimes today would measure tok/s only, while costing three export pipelines (GGUF; `.pte` + QNN lowering; MLC/TVM) and three sampler implementations. Given the first attempt already failed on garbled output, that is risk multiplied for no answer.

Mitigation is structural: the seam carries the full contract, and `BenchmarkScreen` ships in V0.1 so a new engine is a plug-in, not a rewrite.

### The `LLMEngine` seam carries the contract, not just a string
**Status:** settled, and the reasoning matters.

Rejected:
```kotlin
interface LLMEngine { suspend fun generate(prompt: String, context: String = ""): String }
```

`prompt: String` assumes the prompt is runtime-independent. It isn't — the model is fused to a 1024-byte prefix, an empty `<think>` block, the `tool`-renders-as-`user` quirk, and `addSpecial=false`. Each runtime tokenizes and templates differently. Behind `generate(prompt)`, each engine would build its own bytes and send **different input to the same weights** — so the benchmark would report *"ExecuTorch is worse at Hinglish"* when the real cause is a template bug. Invalid in a nearly invisible way.

The interface therefore carries `PromptTemplate`, `ToolCallParser`, `PrefixCacheKey`, `SamplerProfile`, and a measured `turnTokenBudget`. Every engine renders through the same template and passes the same golden byte/token test. See [CONTRACT.md](CONTRACT.md) §7.

Two things the interface does *not* assume: `prefixCacheKey` is llama.cpp-specific (others return an equivalent or no-op `warmPrefix()`), and `SamplerProfile.Grammar` is GBNF, which ExecuTorch/MLC lack natively — they fall back to `Greedy` + `JsonSalvage`, which is itself a benchmark finding worth recording.

### Hand-roll the prompt builder
**Status:** settled.

Do **not** use `llama_chat_apply_template`. `enable_thinking` is a *template kwarg* with no clean path through llama.cpp's C API (`HANDOFF.md:32` shows it only as a CLI flag), so the empty-think block can't be guaranteed. A hand-rolled builder is testable against `transformers`-rendered fixtures and gives the prefix/turns split prefix caching needs.

---

## Inference configuration

### q8_0 K / q8_0 V; q4_0 on K forbidden
**Source:** `reports/On device phone action tools.md:35`. **Status:** settled.

q4_0 on K alone collapsed Qwen2.5-7B from 92.0% → 24.2%; llama.cpp's docs warn `-ctk q4_0` "can substantially degrade tool calling performance"; Qwen's measured KV-quant damage concentrates in **tool calling**. No KV-quant data exists for any sub-1B model, and with only 8 KV heads a 0.6B model plausibly tolerates K error *worse*. q8_0/q8_0 is the start; q4_0 K is off the table until the model's own eval clears it.

### `n_ctx` = 1024
**Source:** `HANDOFF.md:7,32`; `MAX_LEN=1024` in the notebook. **Status:** settled.

Longest training sample is ~811 tokens. Raising `n_ctx` costs RAM superlinearly against the ~500 MB gate (4096 at q8_0 ≈ 244 MB of KV) and runs the model beyond anything it saw in training.

### Sampler: chat defaults, then greedy+grammar once parsing
**Source:** `reports:78`. **Status:** settled.

Chat uses Qwen3 non-thinking defaults (0.7 / 0.8 / 20). The moment `<tool_call>` appears — or whenever output will be parsed — switch to greedy under GBNF. Qwen2.5-0.5B's recovered tool-call rate fell **72% → 32%** under sampling.

`repeat_penalty` is **undecided** — only `--temp 0` was ever tested, and the prior attempt failed on repetitive output. Choose it with a test, not by default.

### Prefix KV cache built on-device, never shipped
**Source:** `reports:31`, `context_cost_optimization.md:153`. **Status:** settled.

State format and numerics depend on the llama.cpp build and backend, so a precomputed file in the APK is unsafe. Build at first launch, key on `model + build + KV type + n_ctx + prefix text`. **Nothing dynamic in the cached prefix** — a timestamp alone breaks reuse.

---

## App architecture

### Chunked map-reduce for long recordings
**Who:** user. **Status:** settled.

A 30-minute recording cannot fit 1024 tokens (~730 usable ≈ 2–4 minutes of speech). Map each ~420-token chunk → reduce the summaries. Alternatives rejected: capping V0.1 at 3-minute recordings (too limiting), and raising `n_ctx` (breaks the memory gate and exceeds the training distribution).

Map/reduce prompts deliberately mirror the trained phrasing (`"Summarize ... in 3 bullet points:\n\n<text>"`) — staying inside the trained instruction format is the highest-leverage choice in that step.

### Tasks/decisions: 3-tier ladder with an honesty column
**Status:** settled, because the feature is out of distribution.

1. **GBNF-constrained JSON, greedy**, run on the *reduce bullets* — shorter, and already in-distribution output the model produced itself. Grammar guarantees syntax, not semantics; eliminating the syntax failure mode is free value.
2. **`JsonSalvage`** — repair truncated JSON by depth tracking. Marked `MODEL_SALVAGED`.
3. **Heuristic regex, no model** — `karna hai`, `TODO`, `decided`, `tay hua`. Marked `HEURISTIC`. **This tier is what makes V0.1 demoable on the untuned model.**

Every task/decision row carries `extractionConfidence`, so the UI can say "low confidence" instead of lying. That single column is what makes a stock-model V0.1 honest rather than broken.

`dueHint` stays a **String** — never let a 0.6B model's date guess become a timestamp. `reports:60-62` is explicit that date arithmetic belongs in Kotlin: the most common LLM error on durations is being off by exactly one day, and 3B-class models got 58–66% of temporal primitives wrong.

### sherpa-onnx with `whisper-tiny.en`, offline recognizer + Silero VAD
**Who:** user chose sherpa-onnx. **Status:** settled for V0.1, with a documented limitation.

**There is no prior STT research in the repo** — zero hits for sherpa-onnx, whisper, Vosk, VAD or `SpeechRecognizer` across all 14 notes and 5 reports. So this is greenfield, and inherits no validation either.

**Model choice, stated honestly:** no production Roman-script Hinglish ASR model exists. Hindi models emit Devanagari, which `dataset_builder.py`'s `is_hinglish` rejects. V0.1 ships `whisper-tiny.en` int8 (~40 MB) and documents the limitation, because STT quality is not what V0.1 proves — the pipeline and the prompt bytes are. `SttEngine` is an interface, so upgrading is a config change.

**Offline, not streaming:** whisper is encoder-decoder; transcription happens after stop; batching VAD segments is more accurate than a streaming zipformer.

**Silero VAD is required, not optional** — whisper hallucinates confidently on silence, and VAD segments give real `TranscriptSegment` timestamps for free.

### Room/SQLite + FTS4
**Who:** user chose SQLite. **Status:** settled. No prior DB research in the repo; unconstrained.

### Models side-loaded via adb, not bundled
**Status:** settled for V0.1.

40 MB STT + 378 MB GGUF would make a ~420 MB APK. Models live in `filesDir/models/`. A real downloader is deferred until there's a real user.

### STT and LLM never loaded at the same time
**Status:** settled — this is what keeps the memory gate reachable.

397 MB weights + ~60 MB KV + ART/Compose/Room/onnxruntime is already tight against ~500 MB. `TranscribeWorker` must unload the ONNX model **before** `SummarizeWorker` loads the LLM. The two workers are chained precisely so this is enforceable, with a process-wide `Mutex` around the engine singleton (unloads after 60 s idle).

---

## Reversals already in the research

Worth knowing, so an older report isn't cited as current:

- **Separate router model: proposed, then rejected.** `reports/Tool calling failures in small LLMs.md` recommended splitting routing from generation. The later `On device phone action tools.md:37` reverses it: *"A 100M+-parameter classifier beside a 397MB model breaks the budget"*, the practitioner threshold is ~15 tools, and *"a static, cached, 240-token prefix already delivers the short prompt, and dynamically retrieved tools would defeat the cache."* Later report wins.
- **`send_message` phasing.** `reports:101` puts it in phase 2, but it **is** in the shipped system prompt and the training data. The data wins.
- **Embeddings / RAG for semantic memory: rejected on RAM.** No embedding model fits beside the LLM. One practitioner figure is damning for edge RAG: ~1000 tokens of retrieved context can take *"up to 50 seconds"* on target embedded hardware (`architecture_patterns.md:91`). V0.1's Memory "Ask" is FTS retrieval + a single-shot answer, capped at ~400 tokens of context.
