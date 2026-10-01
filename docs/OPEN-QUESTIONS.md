# Open questions

What is still unknown, how to measure it, and what changes depending on the answer. Ordered by how much damage a wrong guess does.

A recurring theme: the research repo is unusually honest that **almost nothing has been measured on a phone**. `model_comparison.md:138` — *"No published, measured on-phone RSS numbers (e.g., llama.cpp on Android) found … the RAM totals above are standard estimates, not citations."* The ~100 tok/s figure quoted throughout is **laptop CPU prompt-processing**, not phone decode (`reports/On device phone action tools.md:7`). Treat every performance number in `reports/` as arithmetic until the app reproduces it.

---

## Measured on a real phone (2026-10-01)

Device: Motorola Edge 60 Fusion, MediaTek MT6878 (Dimensity 7400), 7.6 GB RAM, Android 16, arm64-v8a. llama.cpp `b11312`, Release native build, Qwen3-0.6B Q4_K_M (stock), `n_ctx` 1024, q8_0/q8_0 KV, flash attention on, 4 threads.

| Question | Result |
|---|---|
| Q2 System prefix tokens | **299** (docs estimated 294). Measured with the real tokenizer at load. |
| Q3 Decode speed | **~15 tok/s** greedy on a short Hinglish answer (26 tokens). Longer runs and thermals not yet measured. |
| Q9 Prefix cache | cold prefill **6.3 s** (299 tokens), warm load **12 ms**, identical greedy output after restore. |
| Prompt contract | Bytes and token ids equal `transformers` for all 9 golden fixtures, including the `tool`-as-`user` rendering. |
| Grammar | `tool_call.gbnf` forced a valid `set_timer` call on the untuned model; the parser reads it. |
| **Q1 Memory** | **500 MB gate MISSED: 638 MB PSS** (726 MB RSS) with the model loaded and one generation done. |

PSS breakdown (KB): private-other 384,980 (the mmapped GGUF), native-heap 112,692 (KV ~60 MB + compute buffers), code 122,520, java-heap 10,248, system 7,618, swap 30,780.

The weights alone (~385 MB) plus KV and buffers (~113 MB) already reach ~500 MB, so lowering `n_ctx` cannot close the gap. The weights are clean file-backed pages the OS can reclaim, so anonymous memory (native heap + java heap, ~125 MB) may be the better metric. **The gate needs a decision**; until then `MemoryGate` uses a 700 MB regression ceiling.

An unoptimized (Debug) native build is ~50x slower and made the first runs meaningless; the Gradle config now forces `-DCMAKE_BUILD_TYPE=Release` for every variant.

---

## Blocking — measure before the code that depends on them

### 1. Does the model + KV actually fit under 500 MB on the phone?
**Unknown.** Budget arithmetic says 397 MB weights + ~60 MB KV at `n_ctx` 1024, but *"the compute-buffer and app overhead has not been measured, so these margins are upper bounds until someone reads real on-device RSS"* (`reports:21`).

**Measure:** plan step 3 — load the GGUF, then `adb shell dumpsys meminfo com.nishu.app`, TOTAL PSS. Also expose `rssBytes()` through JNI so an instrumented test can assert it, since the release gate (`reports:133`) is a number CI must be able to check.

**If it fails:** reduce `n_ctx` before building anything else. This is why it's step 3, not step 12.

**Compounding risk:** onnxruntime (STT) + llama.cpp together will certainly exceed it. Mitigated by never loading both — verify with `dumpsys meminfo` at the worker boundary.

### 2. Is the system prefix really 294 tokens?
**Unknown.** Nothing in the repo measures it. Plausible for 1024 ASCII chars, unconfirmed.

**Measure:** `llama_tokenize` on the prompt asset at first launch; log and store it.

**If it's ~340:** the ~730-token turn budget drops ~8% and every chunk size must shrink. Measured at step 4, before step 11 depends on it. **Do not hardcode 294.**

### 3. Decode speed on the target phone
**Unknown.** No tokens/sec decode figure for any phone exists in the repo.

**Measure:** step 5 logs tok/s on a known row. That one number sets every timeout, the map-reduce wall-time estimate, and whether chunked summarization is usable at all.

**If it's ~8 tok/s:** 20 chunks × ~160 gen tokens ≈ 7 minutes for a 30-minute recording. Acceptable as background work. **If it's ~3 tok/s:** cap V0.1 at ~12 chunks (~25 min audio) and say so in the UI.

---

## Product-shaping

### 4. Hinglish STT — the hardest unsolved problem here
**Unknown, and probably not solvable in V0.1.** No Roman-script Hinglish ASR model exists in production. Hindi models emit Devanagari, which `dataset_builder.py`'s `is_hinglish` rejects outright.

This compounds with a risk the research already flags: the whole tool design depends on the model receiving Hinglish spelling variants (`sawa`/`sava`/`savva`, `baje`/`bje` — `reports:84`), and *"The open risks are Hinglish noise, which no published system has measured"* (`reports:90`). ASR error now stacks on top of that.

**Paths to evaluate, in rough order of promise:**
- `whisper-base`/`small` int8 with `language="hi"`, plus a Devanagari → Roman transliteration post-step (indic-transliteration or a hand table). Heavier; measure RAM against Q1.
- Whisper multilingual's native code-switching, which sometimes emits Roman Hindi directly — unmeasured.
- A streaming zipformer trained on Indian English — better on the English half, worse on Hindi.
- Collect real utterances from your own use and build a small labelled set. Probably the only thing that actually answers it.

**Measure:** record ~30 real Hinglish utterances (including the `sawa/saade/paune` time expressions and code-switched sentences), transcribe with each candidate, and compute WER by hand. Small, boring, and the only way to know.

**V0.1 stance:** ship `whisper-tiny.en`, document the limitation in the UI, keep `SttEngine` an interface.

### 5. Can a 0.6B model extract tasks/decisions usefully at all?
**Unknown, and currently unlikely.** Zero training rows extract JSON from a transcript. GBNF guarantees *parseable* JSON, not *correct* JSON. Structured-output benchmarks cap value accuracy for text fields around 83% even when schema compliance is perfect (`reports:64`).

**Measure:** after the fine-tune exists, run the 3-tier ladder over ~10 real recordings and hand-score tier 1 output against the heuristic tier. The honest expectation on the untuned model is that **tier 3 heuristics win**.

**If the model loses even after fine-tuning:** two options — add transcript→JSON extraction examples to the training set (the generator in `dataset_builder.py` already knows how to validate by execution), or accept heuristics as the primary extractor and use the model only for the summary, which it *is* trained for.

### 6. Does the call → result → reply loop work?
**Unknown.** 342 of 1,462 tool conversations (23%) end on the tool call with no result and no reply (`dataset_builder.py:697`). So the model may not reliably produce a second assistant turn after `<tool_response>` is injected.

**Measure:** once the fine-tune exists, feed a tool result and check whether a reply follows without an explicit re-prompt.

**If it doesn't:** add an explicit re-prompt, or rebalance that bucket in the next data build. Low priority for the recorder product (which doesn't call tools), but blocking for phone actions later.

---

## Runtime comparison (post-training)

### 7. Does ExecuTorch + QNN actually beat llama.cpp CPU here?
**Unknown — this is the question `BenchmarkScreen` exists to answer.** The research compared runtimes exactly once, for an *embedded Linux* project where llama.cpp beat MLC/ExecuTorch (`architecture_patterns.md:90`). Not an Android finding, and NPU access was not the variable.

**Known for certain:** llama.cpp cannot use the Snapdragon GPU with Q4_K_M (Adreno OpenCL supports only Q4_0/Q6_K). So llama.cpp's CPU number is its ceiling, and the GPU/NPU are entirely unused today.

**Measure:** step 14's suite — TTFT, decode tok/s, prefill tok/s, peak RSS, battery delta, thermal status, SoC, JSON reliability, Hinglish quality — over the same ~20 held-out rows from `test.jsonl` for every engine. Same rows, or the comparison is meaningless.

**The catch:** the two deciding metrics (Hinglish quality, JSON reliability) are **meaningless until the fine-tune exists**. Until then the suite only establishes a speed/memory baseline and proves the harness works.

### 8. What quantization will the QNN path force?
**Unknown.** NPU backends typically want their own quantization scheme rather than Q4_K_M.

**Why it matters beyond speed:** a different quant means a different model artifact, which means **re-running the whole eval** — the Less-is-More study found *"substantial"* function-calling drops under llama.cpp quantization while TinyAgent saw none at 4-bit (`reports:86`). Evidence is mixed, so each quant must be measured, not assumed equivalent.

### 9. Is prefix-cache restore actually faster on this phone?
**Unknown for sub-1B on Android.** `context_cost_optimization.md:156-158` lists this as an explicit gap: no rigorous Android benchmark of `llama_state_load_file` vs prefill, and it's unclear whether `llama-cli --prompt-cache` survived the 2025–26 CLI refactors (the C API is confirmed current). The one Android datapoint (Pixel 8, 820 ms → 310 ms) is a blog, self-described as low-confidence.

**Measure:** step 7 logs cold prefill ms vs warm load ms. Also deliberately corrupt the cache key and confirm a rebuild rather than a stale load.

**Also unknown:** whether state files are portable across ARM SoCs. Irrelevant while caches are built on-device, which is the plan.

---

## Platform risks

### 10. Will OEM ROMs kill a long recording?
**Likely, on some.** Xiaomi/MIUI, Oppo, Vivo aggressively kill foreground services. No research in the repo.

**Measure:** step 8 — a 10-minute screen-off recording on the real target phone, checking for truncation. Do this early; it's cheap and it invalidates the product if it fails.

**Mitigations if it bites:** `PARTIAL_WAKE_LOCK` (already planned), battery-optimization exemption prompt, and appending raw PCM live so a kill loses only the tail rather than the file.

### 11. Do OEM clock apps honour `EXTRA_SKIP_UI`?
**Unverified** for MIUI, Vivo, Oppo (`reports:86`). Only matters when phone actions land — not V0.1. A small device matrix belongs in that release checklist.

### 12. What `repeat_penalty` should be used?
**Never decided.** Only `--temp 0` was ever tested; `top_p`/`top_k`/`repeat_penalty` are unspecified anywhere in the repo.

This deserves an explicit test rather than accepting a llama.cpp default, because **the prior SmolLM2 attempt failed on garbled and repetitive output** — so repetition behavior is a known sensitivity for this project, not a theoretical one.

**Measure:** once the fine-tune exists, sweep `repeat_penalty` over a fixed prompt set and check for loops using the same repetition rule `dataset_builder.py` already enforces on training data.

---

## Not questions — settled, listed to prevent re-asking

| Looks open | Actually settled | Where |
|---|---|---|
| Which base model? | Qwen3-0.6B, full fine-tune | `HANDOFF.md` "Pakke faisle" |
| Try SmolLM2-360M again? | No — English-only base is the root cause, not a pipeline bug | `reports/Hinglish model choice…:3,19` |
| KV quant more aggressively? | No — q4_0 on K collapses tool calling | `reports:35` |
| Add a router/classifier model? | No — breaks the RAM budget under ~15 tools | `reports:37` |
| Embeddings for semantic memory? | No — no RAM; edge RAG can cost ~50 s | `architecture_patterns.md:91` |
| Ship a precomputed KV state file? | No — format depends on build/backend | `reports:31` |
| Timestamp in the system prompt? | No — breaks prefix-cache reuse | `reports:33` |
