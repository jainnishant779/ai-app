# Nishu — Android app

A local-first Hinglish conversation recorder for Android. Records a conversation, transcribes it **on-device**, and extracts a summary, tasks and decisions. No cloud, no audio leaving the phone.

Built around **Nishu**, a fine-tuned Qwen3-0.6B that speaks Hinglish and English. The model/data side (fine-tune pipeline, 6,212 training conversations, research notes) lives in a separate repo.

## Status

**Planning complete, implementation not started.**

| | |
|---|---|
| App code | Not started — V0.1 planned in detail |
| Trained model | Does not exist yet; GPU training has not run |
| V0.1 target | Stock untuned Qwen3-0.6B, so the pipeline is testable today |
| Runtime | llama.cpp is the **first engine**, not a final choice |
| Hardware | Snapdragon, arm64-v8a |

## Docs

Start with [`docs/README.md`](docs/README.md).

| Doc | Read it when |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | Building V0.1 — 14 steps, each verifiable on a device |
| [docs/CONTRACT.md](docs/CONTRACT.md) | **Before writing any prompt, tokenizer or engine code** |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Wondering why something was done this way |
| [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md) | Checking what's still unknown, and how to measure it |

## V0.1 at a glance

```
phone mic → AudioRecord (16kHz mono PCM16)
              ↓
          Silero VAD
              ↓
    sherpa-onnx (offline whisper)
              ↓
         transcript → Room/SQLite
              ↓
   chunked map-reduce (n_ctx is 1024)
              ↓
   Qwen3-0.6B via llama.cpp JNI
              ↓
   summary + tasks + decisions
```

Screens: **Home** (record, recent) · **Conversation** (audio, transcript, summary, tasks, decisions) · **Memory** (search, ask).

## The one thing to know before writing code

The prompt is a **byte-level contract**. The training data baked an exact 1024-byte system prefix into all 6,212 examples, so the app must reproduce it exactly — `sha256 0d30a62b…`. A one-byte drift shifts tokenization and degrades output in a way that reads as *"the model is bad"* rather than as a bug.

Two specific traps, both documented in [docs/CONTRACT.md](docs/CONTRACT.md):

- The copy at `llama_bin/sys_test.txt` in the model repo is **CRLF, 1035 bytes** — not the real prompt. Never ship it.
- A `tool` role message renders as `<|im_start|>user` wrapping `<tool_response>`, **not** `<|im_start|>tool`.

Both are enforced by an on-device test that asserts prompt bytes *and* token IDs against `transformers`-rendered fixtures.
