"""Compare GGUF models on the Nishu test set exactly as the phone runs them: llama.cpp, greedy, thinking off.

Usage: .\\.venv\\Scripts\\python.exe tools\\eval_llm_compare.py <name>=<model.gguf> [<name>=<model.gguf> ...] <out-dir>

Per model it starts llama-server, renders every prompt with the training notebook's chat template
(transformers, enable_thinking=False) and scores:
  - tool eval from the Colab notebook (Cell 7): tool name, exact arguments, false triggers on 150 chat rows
  - every test row: ROUGE-L F1 of the first assistant turn against the gold answer, by category
  - app prompts: the summarizer's map prompt and the extraction prompt on real transcripts (written out side by side)
"""
import json
import random
import re
import subprocess
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

from transformers import AutoTokenizer

SERVER = r"D:\nishant\llm_research\llama_bin\llama\llama-server.exe"
TEST = Path(r"D:\nishant\llm_research\data\v2\final\test.jsonl")
TOKENIZER = Path(r"D:\nishant\ai-app\final-model-20261003T072121Z-1-001\final-model")
SYSTEM_PROMPT_BIN = Path(r"D:\nishant\ai-app\app\src\main\assets\system_prompt.bin")
PORT = 8091
tok = AutoTokenizer.from_pretrained(TOKENIZER)


def render(msgs):
    return tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)


def complete(prompt, n):
    body = json.dumps({"prompt": prompt, "n_predict": n, "temperature": 0, "top_k": 1, "cache_prompt": True}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/completion", body, {"Content-Type": "application/json"})
    r = json.load(urllib.request.urlopen(req, timeout=600))
    return r["content"].replace("<|im_end|>", "").strip(), r.get("timings", {})


def start(model):
    p = subprocess.Popen([SERVER, "-m", model, "-c", "2048", "-t", "8", "--port", str(PORT), "-np", "1"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(120):
        try:
            if json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2)).get("status") == "ok":
                return p
        except Exception:
            pass
        time.sleep(1)
    p.kill()
    raise RuntimeError("llama-server did not start")


def parse_call(text):
    m = re.search(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", text, re.S)
    if not m:
        return None
    try:
        c = json.loads(m.group(1))
        args = c.get("arguments", {})
        return c.get("name"), json.loads(args) if isinstance(args, str) else args
    except json.JSONDecodeError:
        return ("<unparseable>", {})


def words(t):
    return re.findall(r"\w+", t.lower())


def rouge_l(pred, gold):
    a, b = words(pred), words(gold)
    if not a or not b:
        return 0.0
    prev = [0] * (len(b) + 1)
    for x in a:
        cur = [0]
        for j, y in enumerate(b, 1):
            cur.append(prev[j - 1] + 1 if x == y else max(prev[j], cur[j - 1]))
        prev = cur
    lcs = prev[-1]
    if lcs == 0:
        return 0.0
    p, r = lcs / len(a), lcs / len(b)
    return 2 * p * r / (p + r)


def looping(t):
    w = words(t)
    return len(w) >= 24 and len(set(w)) / len(w) < 0.35


def first_assistant(row):
    i = next(k for k, m in enumerate(row["messages"]) if m["role"] == "assistant")
    return i, row["messages"][i]


def app_prompts():
    """The app's own prompts (Prompts.kt as committed, the trained phrasing) on real transcripts."""
    system = SYSTEM_PROMPT_BIN.read_text(encoding="utf-8")
    texts = {
        "meeting (English)": Path(r"D:\nishant\toolchain\stt-eval\qwen3_meeting_a.txt").read_text(encoding="utf-8").split("\n", 1)[1][:1600],
        "user recording 12 (Hinglish, Swift STT)": "Hai saari baatachit aur sun raha hai apne andar rakhata hai aur isko bhi transfer kar rahe hain matlab bol rahi hoon sir is beach mein convert karega. To to meri baat to sun matlab yah app hai kya kis chiz ka hai kya kaam kar rahe hain? Jo bhi device banaaya na uska app hai. To vah yah vaala picture to us app mobile usmen daalega. Haan. Nahin, usse yah kah rahe hain kar doonga. Abhi yah mulaara mic se kar raha hoon usmen bas ek aur mic star par. Usmen mic rah jaega to vah isse connect hua hai.",
        "office task (Hinglish)": "Rahul kal tak client ko proposal bhej dena. Aur Priya, Friday ko design review hai, slides ready rakhna. Budget ke baare mein decide hua ki 2 lakh se zyada nahi jayega. Main Monday ko vendor se baat kar lunga.",
    }
    out = []
    for name, t in texts.items():
        out.append((name, "map", [{"role": "system", "content": system},
                                  {"role": "user", "content": f"Summarize this conversation in 3 bullet points:\n\n{t}"}], 160))
        out.append((name, "extract", [{"role": "system", "content": system},
                                      {"role": "user", "content": f"Extract the tasks and decisions from these notes as JSON:\n\n{t}"}], 220))
    return out


def evaluate(name, model, out_dir):
    rows = [json.loads(l) for l in TEST.open(encoding="utf-8") if l.strip()]
    tool_rows = [r for r in rows if first_assistant(r)[1].get("tool_calls")]
    chat_rows = [r for r in rows if not first_assistant(r)[1].get("tool_calls")]
    chat_sample = random.Random(0).sample(chat_rows, min(150, len(chat_rows)))
    sample_ids = {id(r) for r in chat_sample}

    server = start(model)
    try:
        results = []
        t0 = time.time()
        decode_tps = []
        for n, row in enumerate(rows):
            i, gold = first_assistant(row)
            pred, timing = complete(render(row["messages"][:i]), 200 if not gold.get("tool_calls") else 96)
            if timing.get("predicted_per_second"):
                decode_tps.append(timing["predicted_per_second"])
            results.append({"category": row["category"], "gold": gold, "pred": pred, "in_chat_sample": id(row) in sample_ids,
                            "is_tool": bool(gold.get("tool_calls"))})
            if n % 40 == 0:
                print(f"  [{name}] {n}/{len(rows)} rows, {time.time() - t0:.0f}s", flush=True)
        app = [(src, kind, complete(render(msgs), n)[0]) for src, kind, msgs, n in app_prompts()]
    finally:
        server.kill()

    # Tool eval, as in the notebook
    tool_total = name_ok = args_ok = chat_total = false_trig = 0
    per_tool, per_tool_ok = Counter(), Counter()
    failures = []
    for r in results:
        pred = parse_call(r["pred"])
        if r["is_tool"]:
            f = r["gold"]["tool_calls"][0]["function"]
            g = json.loads(f["arguments"]) if isinstance(f["arguments"], str) else f["arguments"]
            tool_total += 1
            nm = bool(pred and pred[0] == f["name"])
            ok = bool(nm and pred[1] == g)
            name_ok += nm
            args_ok += ok
            per_tool[f["name"]] += 1
            per_tool_ok[f["name"]] += ok
            if not ok:
                failures.append({"gold": {f["name"]: g}, "pred": r["pred"][:200]})
        elif r["in_chat_sample"]:
            chat_total += 1
            false_trig += pred is not None

    # Text quality by category (non-tool rows)
    by_cat = defaultdict(list)
    loops = 0
    for r in results:
        if r["is_tool"]:
            continue
        by_cat[r["category"]].append(rouge_l(r["pred"], r["gold"].get("content") or ""))
        loops += looping(r["pred"])

    summary = {
        "model": name,
        "tool_name_acc": f"{name_ok}/{tool_total}",
        "tool_exact_args": f"{args_ok}/{tool_total}",
        "false_triggers": f"{false_trig}/{chat_total}",
        "per_tool_exact": {t: f"{per_tool_ok[t]}/{n}" for t, n in per_tool.most_common()},
        "rougeL_by_category": {c: round(sum(v) / len(v), 3) for c, v in sorted(by_cat.items())},
        "rougeL_all_text": round(sum(sum(v) for v in by_cat.values()) / sum(len(v) for v in by_cat.values()), 3),
        "looping_answers": loops,
        "decode_tok_s_desktop": round(sum(decode_tps) / max(1, len(decode_tps)), 1),
        "seconds": round(time.time() - t0),
    }
    (out_dir / f"{name}_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / f"{name}_rows.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in results), encoding="utf-8")
    (out_dir / f"{name}_tool_failures.json").write_text(json.dumps(failures, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / f"{name}_app.json").write_text(json.dumps(app, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=1), flush=True)


def main():
    *pairs, out = sys.argv[1:]
    out_dir = Path(out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for p in pairs:
        name, model = p.split("=", 1)
        evaluate(name, model, out_dir)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
