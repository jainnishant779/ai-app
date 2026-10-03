"""Compare base Qwen3-0.6B vs fine-tuned Nishu model on test.jsonl.

Runs both models on the same held-out test set and produces a
side-by-side accuracy comparison table.

Usage:
  $env:PYTHONHOME=$null; $env:PYTHONPATH=$null
  .\.venv\Scripts\python.exe tools\compare_base_vs_finetuned.py

  # Run only base model (skip finetuned if not downloaded yet):
  .\.venv\Scripts\python.exe tools\compare_base_vs_finetuned.py --base-only

  # Run only finetuned model:
  .\.venv\Scripts\python.exe tools\compare_base_vs_finetuned.py --ft-only
"""
import argparse
import copy
import json
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# ── Paths ──────────────────────────────────────────────────────────────
TEST_FILE = Path(r"D:\nishant\llm_research\data\v2\final\test.jsonl")
BASE_MODEL = "Qwen/Qwen3-0.6B"
FT_MODEL   = Path(r"D:\nishant\ai-app\final-model-20261003T072121Z-1-001\final-model")

# ── Tool-call regex (same as the app) ──────────────────────────────────
TOOL_RE = re.compile(
    r"<tool_call>\s*(\{.*?\})\s*</tool_call>",
    re.DOTALL,
)


def load_test_rows():
    rows = [json.loads(l) for l in TEST_FILE.open(encoding="utf-8")]
    tool_rows = []
    chat_rows = []
    for r in rows:
        has_tc = any(m.get("tool_calls") for m in r["messages"])
        if has_tc:
            tool_rows.append(r)
        else:
            chat_rows.append(r)
    return tool_rows, chat_rows


def to_template_messages(messages):
    """Convert JSONL format → template-compatible (arguments as dict)."""
    out = copy.deepcopy(messages)
    for m in out:
        for tc in m.get("tool_calls") or []:
            fn = tc["function"]
            if isinstance(fn["arguments"], str):
                fn["arguments"] = json.loads(fn["arguments"])
    return out


def build_prompt(tokenizer, messages):
    """Build the input prompt (everything except the assistant response)."""
    # Take messages up to (but not including) the last assistant message
    last_asst = max(i for i, m in enumerate(messages) if m["role"] == "assistant")
    input_msgs = to_template_messages(messages[:last_asst])

    return tokenizer.apply_chat_template(
        input_msgs,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )


def get_expected(messages):
    """Extract the expected assistant response."""
    for m in reversed(messages):
        if m["role"] == "assistant":
            content = m.get("content", "")
            tool_calls = m.get("tool_calls") or []
            if tool_calls:
                # Reconstruct expected tool call text
                parts = []
                for tc in tool_calls:
                    fn = tc["function"]
                    args = json.loads(fn["arguments"]) if isinstance(fn["arguments"], str) else fn["arguments"]
                    parts.append(fn["name"])
                return {"type": "tool_call", "names": parts, "tool_calls": tool_calls, "content": content}
            else:
                return {"type": "chat", "content": content}
    return {"type": "chat", "content": ""}


def generate(model, tokenizer, prompt, max_new=256):
    """Generate response from a prompt string."""
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(
            **inputs,
            max_new_tokens=max_new,
            do_sample=False,           # greedy for deterministic comparison
            temperature=1.0,
            top_p=1.0,
        )
    # Decode only the new tokens
    new_tokens = out[0][inputs["input_ids"].shape[1]:]
    return tokenizer.decode(new_tokens, skip_special_tokens=True).strip()


def parse_tool_calls(text):
    """Extract tool calls from generated text."""
    matches = TOOL_RE.findall(text)
    results = []
    for m in matches:
        try:
            obj = json.loads(m)
            results.append(obj)
        except json.JSONDecodeError:
            pass
    return results


def evaluate_model(model, tokenizer, tool_rows, chat_rows, label="Model"):
    """Run eval on all rows, return metrics dict."""
    print(f"\n{'='*60}")
    print(f"  Evaluating: {label}")
    print(f"  Tool-call rows: {len(tool_rows)}, Chat rows: {len(chat_rows)}")
    print(f"{'='*60}\n")

    # ── Tool-call evaluation ──
    name_correct = 0
    args_correct = 0
    total_tools = 0
    per_tool = defaultdict(lambda: {"total": 0, "exact": 0})
    tool_errors = []

    for i, row in enumerate(tool_rows):
        msgs = row["messages"]
        expected = get_expected(msgs)
        prompt = build_prompt(tokenizer, msgs)
        response = generate(model, tokenizer, prompt, max_new=200)

        parsed = parse_tool_calls(response)

        for j, exp_tc in enumerate(expected.get("tool_calls", [])):
            total_tools += 1
            fn = exp_tc["function"]
            exp_name = fn["name"]
            exp_args = json.loads(fn["arguments"]) if isinstance(fn["arguments"], str) else fn["arguments"]

            per_tool[exp_name]["total"] += 1

            if j < len(parsed):
                gen = parsed[j]
                gen_name = gen.get("name", "")
                gen_args = gen.get("arguments", {})

                if gen_name == exp_name:
                    name_correct += 1

                if gen_name == exp_name and gen_args == exp_args:
                    args_correct += 1
                    per_tool[exp_name]["exact"] += 1
                else:
                    tool_errors.append({
                        "prompt": msgs[-2]["content"] if len(msgs) >= 2 else "",
                        "expected_name": exp_name,
                        "expected_args": exp_args,
                        "got_name": gen_name,
                        "got_args": gen_args,
                    })
            else:
                tool_errors.append({
                    "prompt": msgs[-2]["content"] if len(msgs) >= 2 else "",
                    "expected_name": exp_name,
                    "expected_args": exp_args,
                    "got_name": "<missing>",
                    "got_args": {},
                })

        # Progress
        if (i + 1) % 10 == 0:
            print(f"  Tool rows: {i+1}/{len(tool_rows)}", flush=True)

    print(f"  Tool rows: {len(tool_rows)}/{len(tool_rows)} ✓")

    # ── Chat evaluation (false tool triggers) ──
    false_triggers = 0
    false_trigger_examples = []

    for i, row in enumerate(chat_rows):
        msgs = row["messages"]
        prompt = build_prompt(tokenizer, msgs)
        response = generate(model, tokenizer, prompt, max_new=300)

        parsed = parse_tool_calls(response)
        if parsed:
            false_triggers += 1
            false_trigger_examples.append({
                "prompt": msgs[-1]["content"] if msgs else "",
                "category": row.get("category", "?"),
                "false_call": parsed[0],
            })

        if (i + 1) % 20 == 0:
            print(f"  Chat rows: {i+1}/{len(chat_rows)}", flush=True)

    print(f"  Chat rows: {len(chat_rows)}/{len(chat_rows)} ✓")

    return {
        "label": label,
        "total_tools": total_tools,
        "name_correct": name_correct,
        "args_correct": args_correct,
        "total_chat": len(chat_rows),
        "false_triggers": false_triggers,
        "per_tool": dict(per_tool),
        "tool_errors": tool_errors[:10],  # save first 10
        "false_trigger_examples": false_trigger_examples[:5],
    }


def print_comparison(base_res, ft_res):
    """Print side-by-side comparison table."""
    print("\n" + "=" * 70)
    print("  COMPARISON: Base Qwen3-0.6B  vs  Fine-tuned Nishu")
    print("=" * 70)

    def pct(n, d):
        return f"{n}/{d} ({100*n/d:.1f}%)" if d > 0 else "N/A"

    def delta(ft_val, base_val, total):
        d = ft_val - base_val
        dp = (d / total * 100) if total > 0 else 0
        sign = "+" if d >= 0 else ""
        return f"{sign}{d} ({sign}{dp:.1f}%)"

    rows = [
        ("Tool name accuracy",
         pct(base_res["name_correct"], base_res["total_tools"]),
         pct(ft_res["name_correct"], ft_res["total_tools"]),
         delta(ft_res["name_correct"], base_res["name_correct"], ft_res["total_tools"])),
        ("Exact arguments",
         pct(base_res["args_correct"], base_res["total_tools"]),
         pct(ft_res["args_correct"], ft_res["total_tools"]),
         delta(ft_res["args_correct"], base_res["args_correct"], ft_res["total_tools"])),
        ("False tool triggers",
         pct(base_res["false_triggers"], base_res["total_chat"]),
         pct(ft_res["false_triggers"], ft_res["total_chat"]),
         delta(ft_res["false_triggers"], base_res["false_triggers"], ft_res["total_chat"])),
    ]

    print(f"\n{'Metric':<25} {'Base Qwen3-0.6B':<22} {'Fine-tuned Nishu':<22} {'Delta':<18}")
    print("-" * 87)
    for name, base_v, ft_v, d in rows:
        print(f"{name:<25} {base_v:<22} {ft_v:<22} {d:<18}")

    # Per-tool breakdown
    all_tools = sorted(set(list(base_res["per_tool"].keys()) + list(ft_res["per_tool"].keys())))
    if all_tools:
        print(f"\n{'Per-Tool (exact args)':<25} {'Base':<22} {'Fine-tuned':<22}")
        print("-" * 69)
        for t in all_tools:
            b = base_res["per_tool"].get(t, {"total": 0, "exact": 0})
            f = ft_res["per_tool"].get(t, {"total": 0, "exact": 0})
            bv = pct(b["exact"], b["total"])
            fv = pct(f["exact"], f["total"])
            print(f"  {t:<23} {bv:<22} {fv:<22}")

    # Show base model errors
    if base_res.get("tool_errors"):
        print(f"\n--- Base model errors (first {len(base_res['tool_errors'])}) ---")
        for e in base_res["tool_errors"][:5]:
            print(f"  Prompt: {e['prompt'][:80]}...")
            print(f"    Expected: {e['expected_name']}({e['expected_args']})")
            print(f"    Got:      {e['got_name']}({e['got_args']})")

    if base_res.get("false_trigger_examples"):
        print(f"\n--- Base model false triggers (first {len(base_res['false_trigger_examples'])}) ---")
        for e in base_res["false_trigger_examples"][:3]:
            print(f"  [{e['category']}] {e['prompt'][:80]}...")
            print(f"    False call: {e['false_call']}")

    print()


def print_single(res):
    """Print results for a single model."""
    def pct(n, d):
        return f"{n}/{d} ({100*n/d:.1f}%)" if d > 0 else "N/A"

    print(f"\n{'='*60}")
    print(f"  Results: {res['label']}")
    print(f"{'='*60}")
    print(f"  Tool name accuracy : {pct(res['name_correct'], res['total_tools'])}")
    print(f"  Exact arguments    : {pct(res['args_correct'], res['total_tools'])}")
    print(f"  False tool triggers: {pct(res['false_triggers'], res['total_chat'])}")

    if res["per_tool"]:
        print(f"\n  Per-tool (exact args):")
        for t in sorted(res["per_tool"]):
            v = res["per_tool"][t]
            print(f"    {t:<20} {pct(v['exact'], v['total'])}")

    if res.get("tool_errors"):
        print(f"\n  Errors (first 5):")
        for e in res["tool_errors"][:5]:
            print(f"    [{e['expected_name']}] {e['prompt'][:60]}...")
            print(f"      Expected args: {e['expected_args']}")
            print(f"      Got:           {e['got_name']}({e['got_args']})")

    print()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-only", action="store_true", help="Only evaluate base model")
    parser.add_argument("--ft-only", action="store_true", help="Only evaluate fine-tuned model")
    args = parser.parse_args()

    run_base = not args.ft_only
    run_ft = not args.base_only

    # Check fine-tuned model exists
    if run_ft and not FT_MODEL.exists():
        print(f"ERROR: Fine-tuned model not found at {FT_MODEL}")
        print("Download from Google Drive first, or use --base-only")
        if run_base:
            print("Falling back to --base-only mode.\n")
            run_ft = False
        else:
            sys.exit(1)

    tool_rows, chat_rows = load_test_rows()
    print(f"Test set: {len(tool_rows)} tool-call rows + {len(chat_rows)} chat rows = {len(tool_rows)+len(chat_rows)} total")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Device: {device}")
    dtype = torch.float16 if device == "cuda" else torch.float32

    results = {}

    if run_base:
        print(f"\nLoading base model: {BASE_MODEL} ...")
        t0 = time.time()
        base_tok = AutoTokenizer.from_pretrained(BASE_MODEL, trust_remote_code=True)
        base_model = AutoModelForCausalLM.from_pretrained(
            BASE_MODEL, torch_dtype=dtype, trust_remote_code=True
        ).to(device).eval()
        print(f"  Loaded in {time.time()-t0:.1f}s")

        results["base"] = evaluate_model(base_model, base_tok, tool_rows, chat_rows, label="Base Qwen3-0.6B")

        # Free memory before loading next model
        del base_model, base_tok
        torch.cuda.empty_cache() if device == "cuda" else None

    if run_ft:
        print(f"\nLoading fine-tuned model: {FT_MODEL} ...")
        t0 = time.time()
        ft_tok = AutoTokenizer.from_pretrained(str(FT_MODEL), trust_remote_code=True)
        ft_model = AutoModelForCausalLM.from_pretrained(
            str(FT_MODEL), torch_dtype=dtype, trust_remote_code=True
        ).to(device).eval()
        print(f"  Loaded in {time.time()-t0:.1f}s")

        results["ft"] = evaluate_model(ft_model, ft_tok, tool_rows, chat_rows, label="Fine-tuned Nishu")

        del ft_model, ft_tok
        torch.cuda.empty_cache() if device == "cuda" else None

    # ── Output ──
    if "base" in results and "ft" in results:
        print_comparison(results["base"], results["ft"])
    elif "base" in results:
        print_single(results["base"])
    elif "ft" in results:
        print_single(results["ft"])

    # Save raw JSON
    out_path = Path(__file__).parent / "comparison_results.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2, default=str)
    print(f"Raw results saved to: {out_path}")


if __name__ == "__main__":
    main()
