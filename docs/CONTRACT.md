# Runtime contract

What the app **must** send the model, byte for byte. Everything here was measured against `D:\nishant\llm_research`, not assumed. Where a claim is unverified, it says so.

> **Why this file exists.** `HANDOFF.md:31` — *"Phone app ko bilkul yahi prompt bhejna hai"* ("send the app exactly this prompt"). The training data baked an exact prefix into all 6,212 examples. A one-byte drift shifts tokenization and degrades tool calling and Hinglish quality — and it presents as "the model is bad", not as a bug. This is the hardest class of error to debug here, so it is pinned down with hashes and a test.

---

## 1. The system prompt

```
source   D:\nishant\llm_research\data\v2\final\train.jsonl, line 1, messages[0].content
size     1024 chars / 1024 UTF-8 bytes (pure ASCII)
newlines 11 LF, 0 CR
sha256   0d30a62b8de680791d8acbc8a14e5d62df0d0cca2c4474a3ae63a98aead6f3c3
```

Built at `dataset_builder.py:1392` as `SYSTEM_PROMPT.format(name="Nishu") + "\n\n" + TOOL_SIGNATURES` (constants at `:95-112`). Verbatim:

```
Tum Nishu ho, ek helpful assistant jo Hinglish aur English dono mein baat karta hai. User jis bhasha mein pooche, usi bhasha mein jawab do. Calculation, unit conversion, date, alarm, timer, torch/settings, app kholna, call ya message bhejne ke liye tool use karo.

Tools (jab zaroorat ho, sirf <tool_call>{"name": ..., "arguments": {...}}</tool_call> likho):
calculator(expression:str) | + - * / % ** ( )
unit_converter(value:num, from_unit, to_unit) | km miles kg lbs celsius fahrenheit meters feet liters gallons
date_time(query:today|day_of_week|+N days|-N days)
set_alarm(hour:int, minute:int, period?:morning|afternoon|evening|night, day?:today|tomorrow|day_after_tomorrow|mon..sun, repeat?:daily|weekdays|weekends|mon..sun, label?:str)
set_timer(hours?:int, minutes?:int, seconds?:int, label?:str)
device_control(target:flashlight|wifi|bluetooth|silent|vibrate, state:on|off|toggle)
open_app(app_name:str)
make_call(contact?:str, number?:str) | exactly one
send_message(contact:str, text:str, app:whatsapp|sms|default)
```

No trailing newline. Ends on `...app:whatsapp|sms|default)`.

### ⚠️ Do not use `llama_bin\sys_test.txt`

```
sys_test.txt   1035 bytes, 11 CR + 11 LF
               sha256 030a036ca14f46107ba56e77480a2b402582b38c7045ab4109fc2c6702a91ec6
               first divergence at byte 263
```

It is a CRLF-converted copy. Shipping it changes tokenization at all 11 line boundaries. **Extract the asset from `train.jsonl` with LF endings and assert the hash at build time.**

Also: `gguf/system_prompt.txt` referenced in `HANDOFF.md:31` is a *Google Drive* path written by the training notebook's Cell 8. It does not exist in the repo.

### Rules

- **Ships on every request, including plain chat.** `dataset_builder.py:1390` — every example carries the deployed prefix, so chat teaches "tool list present, no tool needed". Stripping the tool list for chat would be an unseen prompt distribution.
- Store as `system_prompt.bin` (not `.txt`), with `.gitattributes` `-text` so nothing normalizes it, and `noCompress += "bin"` so `AssetManager` returns exact bytes.
- Assert the sha256 at startup — fail fast in debug, log in release.

---

## 2. Prompt rendering

### The generation prompt must end exactly like this

```
<|im_start|>assistant
<think>

</think>

```

`enable_thinking=false` does **not** remove the think block — it pre-fills an empty one. Literally: `<think>`, blank line, `</think>`, blank line, then generation starts. This was true at training time (`HANDOFF.md:30`), so the app must reproduce it. In llama.cpp CLI it comes free via `--chat-template-kwargs '{"enable_thinking": false}'`; when hand-building the string, emit it yourself.

Evidence of what happens without it: `llama_bin/q1.txt` shows the untuned base model burning its entire 160-token budget inside a think block and emitting one useful word.

### Full shape

```
<|im_start|>system
{the 1024-byte prompt}<|im_end|>
<|im_start|>user
{user text}<|im_end|>
<|im_start|>assistant
<think>

</think>

```

### ⚠️ The `tool` role renders as `user`

A tool result is `role: "tool"` in the message list, but the Qwen3 template renders it under a **`user`** header:

```
<|im_start|>user
<tool_response>
{"ok": true, "timer": "00:00:45", "label": "plank"}
</tool_response><|im_end|>
```

**Not** `<|im_start|>tool`. This is the single most likely silent mismatch in the whole contract.

Adjacent tool messages merge into one `user` block with multiple `<tool_response>` blocks, closing `<|im_end|>` only after the last. V0.1 never emits more than one call per turn, so this cannot occur — but encode it in the golden fixtures so a later version does not regress, and assert two TOOL messages are never adjacent.

### ⚠️ `addSpecial` must be `false`

`<|im_start|>` is already in the text. Letting the tokenizer add BOS shifts every token index and silently invalidates the prefix cache.

### Don't use `llama_chat_apply_template`

`enable_thinking` is a *template kwarg* with no clean path through llama.cpp's C API (`HANDOFF.md:32` shows it only as a CLI flag), so the empty-think block cannot be guaranteed. Hand-roll the builder: it is testable against `transformers`-rendered fixtures and gives the prefix/turns split that prefix caching needs.

---

## 3. Tool-call wire format

### Model emits

```
<tool_call>
{"name": "make_call", "arguments": {"contact": "Priya Verma"}}
</tool_call><|im_end|>
```

- Keys are **`name`** and **`arguments`** — not `function`/`parameters`. No `id`, no `type`, no `index`.
- `arguments` is a **real JSON object** on the wire. (In the JSONL it is stored as a serialized *string*; the template expands it. Parse leniently: `if (isString) json.loads(args) else args`.)
- `<tool_call>`, `</tool_call>`, `<tool_response>`, `</tool_response>`, `<think>`, `</think>` are all **single tokens** in the Qwen3 vocab.
- **Exactly one call per turn.** Audited: 0 of 6,212 assistant messages carry more than one `tool_calls` entry. Treat a second block as an anomaly and take the first.

Reference parser (training notebook Cell 7):
```python
re.search(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", text, re.S)
```

### Tool result payload

Compact JSON, `ensure_ascii=False`. Outcome vocabulary (`dataset_builder.py:194`): `ok`, `ambiguous`, `not_found`, `needs_user`, `time_passed`, `invalid_number`, `permission_needed`.

```json
{"ok": true, "timer": "00:00:45", "label": "plank"}
{"error": "contact_not_found", "query": "Sharma ji"}
{"status": "needs_user", "opened": "wifi_panel", "note": "Android apps cannot switch this directly"}
{"status": "ambiguous", "options": ["Rahul Sharma", "Rahul Gupta"]}
```

### Training-data JSONL schema

```jsonc
{"messages": [...]}                           // train.jsonl
{"messages": [...], "category": "decline"}    // test.jsonl adds category
```

Roles: `system`, `user`, `assistant`, `tool`. Assistant tool-call message:

```json
{"role": "assistant", "content": "",
 "tool_calls": [{"type": "function",
                 "function": {"name": "set_timer",
                              "arguments": "{\"seconds\": 45, \"label\": \"plank\"}"}}]}
```

`content` is `""`; `arguments` is a JSON **string** here; no `id`/`tool_call_id` anywhere.

### ⚠️ 23% of tool examples have no result turn

342 of 1,462 tool-calling conversations (the `legacy_tool_call` bucket, `dataset_builder.py:697`) end on the tool call — no result, no final answer. The other 1,120 teach call → result → reply. So **do not assume a second assistant turn always follows naturally** after injecting `<tool_response>`; an explicit re-prompt may be needed. Test on the real model once it exists.

---

## 4. Inference settings

| Setting | Value | Source |
|---|---|---|
| Base model | `Qwen/Qwen3-0.6B` | notebook Cell 4 |
| Quant | GGUF Q4_K_M | Cell 8 |
| `n_ctx` | **1024** | `HANDOFF.md:7,32`; `MAX_LEN=1024` Cell 4 |
| Longest training sample | ~811 tokens | Cell 4 |
| `enable_thinking` | **false** | `HANDOFF.md:30` |
| Temperature (as tested) | **0** (greedy) | `--temp 0 -st`, Cell 9 |
| `top_p` / `top_k` / `repeat_penalty` | **never chosen** | — |
| KV cache | q8_0 K / q8_0 V | `reports:35,100` |

CLI used for testing (Cell 9):
```
llama-cli -m nishu-Q4_K_M.gguf -c 1024 \
  -sysf gguf/system_prompt.txt \
  --chat-template-kwargs '{"enable_thinking": false}' \
  -p "<question>" -n 160 --temp 0 -st
```

### Sampling policy

- **Chat:** Qwen3 non-thinking defaults — temp 0.7, top_p 0.8, top_k 20.
- **Once `<tool_call>` appears, or whenever output will be parsed:** greedy under a GBNF grammar admitting only known tool names and enum values. Qwen2.5-0.5B's recovered tool-call rate fell **72% → 32%** under sampling (`reports:78`).
- `repeat_penalty` was never decided and the prior SmolLM2 attempt failed on repetitive output — pick it deliberately with a test, not by accepting a default.

### ⚠️ q4_0 on the K cache is forbidden

It collapsed Qwen2.5-7B from 92.0% → 24.2%; llama.cpp's own docs warn `-ctk q4_0` "can substantially degrade tool calling performance", and no KV-quant data exists for any sub-1B model (`reports:35`). q8_0/q8_0 is the starting point.

### Memory budget

```
weights (Q4_K_M)        ~397 MB
KV @ n_ctx 1024, q8_0    ~60 MB    (112 KB/token at fp16; ~59.5 KB at q8_0)
release gate             on-phone RSS < ~500 MB   (reports:133)
```

System prefix is ~294 tokens of 1024 → **~730 tokens for the whole conversation.** One tool round trip ≈ 80–100 tokens.

### ⚠️ The 294-token figure is unverified

Nothing in the repo measures it; 294 tokens for 1024 ASCII chars is plausible but unconfirmed. **Read it from `llama_tokenize` at first launch and store it.** Do not hardcode — chunk sizes depend on it.

### GPU is unavailable to this engine

The Adreno OpenCL backend supports only **Q4_0/Q6_K**, so Q4_K_M is CPU-only on llama.cpp. Unlocking the Snapdragon GPU/NPU is what ExecuTorch + QNN is for, later.

---

## 5. Prefix KV caching

llama.cpp can save/restore a sequence's KV state (`llama_state_seq_save_file` / `_load_file`, with `LLAMA_STATE_SEQ_FLAGS_ON_DEVICE`). Persisted-KV research measured TTFT cut **3–10× at 1K tokens**; the one Android anecdote (Pixel 8, 820 ms → 310 ms) is a blog and low-confidence.

**Cache key** = hash of `model file + llama.cpp build + KV type + n_ctx + prefix text`.

Rules:
- **Build it on-device at first launch. Never ship a state file in the APK** — the format depends on the llama.cpp build and backend.
- **Nothing dynamic may enter the cached prefix** — no timestamp, contact names or user data. A timestamp alone breaks reuse. The slot design means the model never needs "now"; the executor knows the clock.
- Caching removes **latency**, not **memory**: llama.cpp allocates the KV buffer from `n_ctx` regardless of tokens used.
- Can cause mild nondeterminism (batch-prefill vs generation logits differ).

---

## 6. Tool schemas

Ground truth is the validators in `dataset_builder.py` (every training row had to pass them), enums at `:188-194`.

> **`tool_executors.py` and `verify_tool_calls.py` are stale v1 code** — 4 tools, a `{"tool","args"}` string format, and a `web_search` tool that is not part of Nishu. Porting from them would build the wrong app.

V0.1 does **not** wire these to Android APIs — the recorder product doesn't need them. The parser and grammar are still built, because they prove the contract and drive JSON extraction. Schemas kept here for when phone actions land.

### Compute tools (`run_tool`, `:152-179`) — strict exact-key matching

| Tool | Params | Notes |
|---|---|---|
| `calculator` | `expression` (str, only key) | `+ - * / % ** ( )`. `^` rejected. Max 120 chars. Exponent ≤ 20. |
| `unit_converter` | `value` (**number, never string**), `from_unit`, `to_unit` | Same-dimension pairs only, both directions |
| `date_time` | `query` (only key) | exactly `today` \| `day_of_week` \| `+N days` \| `-N days` |

Extra or missing keys are a **hard error** for these three (`:157,164,173`).

### Phone tools (`run_phone_tool`, `:469-482`)

| Tool | Required | Optional |
|---|---|---|
| `set_alarm` | `hour` 0-23, `minute` 0-59 | `period`, `day`, `repeat`, `label` — `day`+`repeat` together is rejected |
| `set_timer` | at least one of h/m/s > 0 | `hours` 0-24, `minutes` 0-1440, `seconds` 0-86400, `label`; total 1s-24h |
| `device_control` | `target` (flashlight\|wifi\|bluetooth\|silent\|vibrate), `state` (on\|off\|toggle) | — |
| `open_app` | `app_name` (only key, free-form, as spoken: "insta", "yt") | — |
| `make_call` | **exactly one** of `contact` / `number` (`number` is a digit **string**, not int) | — |
| `send_message` | `contact`, `text` (1-300 chars), `app` (whatsapp\|sms\|default) | — |

Omit optional params rather than sending `null` (`:1147`). Unknown args rejected on all six.

### Behavioral invariants the model was trained against

The app is the executor, so these must hold or the model's wording becomes a lie (`check_phone_reply`, `:495-527`):

- `wifi`/`bluetooth` → **always** `needs_user`. The app opens the settings panel, never toggles. The model says *"panel khol diya"*, never *"on kar diya"*. (`setWifiEnabled()` always fails for apps targeting Android 10+; `BluetoothAdapter.enable()` fails on 13+.)
- `silent`/`vibrate` → may return `permission_needed` (one-time DND access grant).
- `make_call` → **only opens the dialer** (`ACTION_DIAL`, no permission). User taps call.
- `send_message` → **only composes**. User taps send.
- `flashlight` → the one target that succeeds directly (`CameraManager.setTorchMode`).

This gives the strongest safety property for free: **the model can never cause an irreversible outward action on its own**, because every call and message ends on an OS screen a human must tap. Never adopt `ACTION_CALL` or silent `SmsManager`, and put the confirmation gate in the executor, not only in the model.

Emergency numbers are a special case: `ACTION_CALL` cannot dial them but `ACTION_DIAL` can — *"112 pe call karo"* must open the dialer and must never be declined.

---

## 7. How this gets enforced

Two generator scripts plus one on-device test. This is the most valuable code in the plan, because everything here fails *silently*.

1. **`tools/extract_system_prompt.py`** — reads `train.jsonl` line 1, writes `assets/system_prompt.bin` with LF endings, asserts length 1024 and sha256 `0d30a62b…`. Must not read `sys_test.txt`.
2. **`tools/render_golden_prompts.py`** — loads the Qwen3-0.6B tokenizer in `transformers`, calls `apply_chat_template(..., add_generation_prompt=True, enable_thinking=False)` on ~8 rows covering plain chat, summarization, a full `system,user,assistant,tool,assistant` tool row, multi-turn `phone_followup`, and identity. Emits `.prompt.bin` + `.tokens.json`.
3. **`PromptBytesTest`** (instrumented, on device) — asserts **byte equality** against each fixture, *and separately* asserts tokenizer IDs equal the golden IDs. Byte equality alone can hide an `addSpecial` mistake; token-ID equality cannot.

Every engine added later (ExecuTorch, MLC) must pass the same test before any number it produces is believed.
