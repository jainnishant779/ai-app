# Nishu Android — docs

Research aur planning for the **Nishu** Android app: a local-first Hinglish conversation recorder that transcribes on-device and extracts a summary, tasks and decisions — no cloud.

The model/data side lives in a separate repo at `D:\nishant\llm_research` (fine-tune pipeline, 6,212 training conversations, ~20 research notes). This repo is the app. These docs are the bridge between them.

## Files

| File | Read it when |
|---|---|
| [PLAN.md](PLAN.md) | You are about to build V0.1. Step-by-step, each step independently verifiable on a device. |
| [CONTRACT.md](CONTRACT.md) | **Before writing any prompt, tokenizer or engine code.** Measured, byte-level facts the app must honor. Getting these wrong looks like a model bug. |
| [DECISIONS.md](DECISIONS.md) | You are wondering "why was it done this way?" or about to reverse something. |
| [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md) | You want to know what is still unknown, and how to measure it. |

## Where things are

```
D:\nishant\llm_research\          model/data side (separate repo, read-only from here)
├── HANDOFF.md                    project charter; "Pakke faisle" = settled decisions
├── dataset_builder.py            system prompt (:95-112), tool schemas, validators
├── data\v2\final\train.jsonl     6,212 rows; line 1 holds the canonical system prompt
├── data\v2\final\test.jsonl      320 held-out rows; the benchmark suite draws from here
├── reports\                      5 synthesized reports (decision-voice)
│   └── On device phone action tools.md    <- effectively an Android spec; most relevant
├── research_notes\               14 raw evidence notes
└── llama_bin\qwen3-0.6b-Q4_K_M.gguf       stock untuned model; what V0.1 builds against

D:\nishant\ai-app\                this repo (the app)
└── docs\                         you are here
```

## Status

| | |
|---|---|
| App code | **Not started.** V0.1 planned, not implemented. |
| Trained model | **Does not exist.** GPU training has not run (`HANDOFF.md:24`). V0.1 builds against the stock untuned Qwen3-0.6B. |
| Runtime | llama.cpp is V0.1's **first engine**, not a final choice. ExecuTorch (Qualcomm QNN) benchmarked after the fine-tune exists. |
| Target hardware | Snapdragon, arm64-v8a. |

## Two things that will bite

1. **The prompt is a byte-level contract.** `HANDOFF.md:31` — *"Phone app ko bilkul yahi prompt bhejna hai."* A one-byte difference silently degrades output in a way that reads as "the model is bad". See [CONTRACT.md](CONTRACT.md).
2. **`llama_bin\sys_test.txt` is NOT the system prompt.** It is a CRLF copy, 1035 bytes vs the real 1024. Never ship it as the app asset.
