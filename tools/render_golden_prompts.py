"""Render golden prompt fixtures with the real Qwen3 chat template (transformers).

Outputs to app/src/sharedTest/golden/:
  NN_name.messages.json   input messages (Kotlin renders these)
  NN_name.prompt.bin      expected prompt bytes (gitignored via *.bin; regenerate with this script)
  NN_name.tokens.json     expected token ids (add_special_tokens=False)
  tool_calls_all.jsonl    every assistant tool call in train.jsonl, for the parser test
Also saves tools/qwen3_chat_template.jinja (the reference to port to Kotlin).

Run:  $env:PYTHONHOME=$null; .\\.venv\\Scripts\\python.exe tools\\render_golden_prompts.py
"""
import copy
import json
import re
import sys
from pathlib import Path

from transformers import AutoTokenizer

ROOT = Path(__file__).resolve().parent.parent
TRAIN = Path(r"D:\nishant\llm_research\data\v2\final\train.jsonl")
OUT = ROOT / "app" / "src" / "sharedTest" / "golden"
TOK = AutoTokenizer.from_pretrained("Qwen/Qwen3-0.6B")


def roles(r):
    return [m["role"] for m in r["messages"]]


def has_tool_calls(r):
    return any(m.get("tool_calls") for m in r["messages"])


def user_texts(r):
    return [m["content"] for m in r["messages"] if m["role"] == "user"]


def to_template_messages(messages):
    """JSONL stores tool_calls.arguments as a JSON string; the template needs a dict."""
    out = copy.deepcopy(messages)
    for m in out:
        for tc in m.get("tool_calls") or []:
            fn = tc["function"]
            if isinstance(fn["arguments"], str):
                fn["arguments"] = json.loads(fn["arguments"])
    return out


def render(messages):
    return TOK.apply_chat_template(
        to_template_messages(messages), tokenize=False,
        add_generation_prompt=True, enable_thinking=False,
    )


def first(rows, pred):
    for r in rows:
        if pred(r):
            return r
    raise SystemExit("no row matched a selection rule")


def main():
    rows = [json.loads(l) for l in TRAIN.open(encoding="utf-8")]
    plain = lambda r: roles(r) == ["system", "user", "assistant"] and not has_tool_calls(r)

    picks = []  # (name, messages up to and including last non-assistant message)
    picks.append(("plain_chat", first(rows, lambda r: plain(r) and not user_texts(r)[0].startswith("Summarize"))["messages"][:2]))
    picks.append(("summarization", first(rows, lambda r: plain(r) and user_texts(r)[0].startswith("Summarize"))["messages"][:2]))
    picks.append(("hinglish_chat", first(rows, lambda r: plain(r) and re.search(r"\b(hai|kya)\b", user_texts(r)[0], re.I))["messages"][:2]))
    tool_row = first(rows, lambda r: roles(r) == ["system", "user", "assistant", "tool", "assistant"])
    picks.append(("tool_call_prompt", tool_row["messages"][:2]))
    picks.append(("tool_call_with_result", tool_row["messages"][:4]))
    picks.append(("tool_call_no_result", first(rows, lambda r: roles(r) == ["system", "user", "assistant"] and has_tool_calls(r))["messages"][:3]))
    multi = first(rows, lambda r: len(user_texts(r)) >= 2 and roles(r)[-1] == "assistant")
    last_user = max(i for i, m in enumerate(multi["messages"]) if m["role"] == "user")
    picks.append(("multi_turn", multi["messages"][: last_user + 1]))
    picks.append(("identity", first(rows, lambda r: plain(r) and len(user_texts(r)[0]) < 120 and re.search(r"naam kya|creator kaun|aap kaun|tum kaun|your name|who are you", user_texts(r)[0], re.I))["messages"][:2]))

    sys_msg = rows[0]["messages"][0]
    picks.append(("two_tool_messages", [
        sys_msg,
        {"role": "user", "content": "Do two things."},
        {"role": "assistant", "content": "", "tool_calls": [{"type": "function", "function": {"name": "date_time", "arguments": "{\"query\": \"today\"}"}}]},
        {"role": "tool", "content": "{\"ok\": true, \"date\": \"2026-10-01\"}"},
        {"role": "tool", "content": "{\"ok\": true, \"day\": \"thu\"}"},
    ]))

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("0*"):
        old.unlink()
    for i, (name, msgs) in enumerate(picks, 1):
        text = render(msgs)
        base = OUT / f"{i:02d}_{name}"
        base.with_suffix(".messages.json").write_text(json.dumps(msgs, ensure_ascii=False, indent=1), encoding="utf-8")
        base.with_suffix(".prompt.bin").write_bytes(text.encode("utf-8"))
        ids = TOK.encode(text, add_special_tokens=False)
        base.with_suffix(".tokens.json").write_text(json.dumps({"ids": ids}), encoding="utf-8")
        print(f"{base.name}: {len(text.encode('utf-8'))} bytes, {len(ids)} tokens")

    (ROOT / "tools" / "qwen3_chat_template.jinja").write_text(TOK.chat_template, encoding="utf-8")

    n = 0
    with (OUT / "tool_calls_all.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            for m in r["messages"]:
                for tc in m.get("tool_calls") or []:
                    fn = tc["function"]
                    args = json.loads(fn["arguments"]) if isinstance(fn["arguments"], str) else fn["arguments"]
                    rendered = '<tool_call>\n{"name": %s, "arguments": %s}\n</tool_call>' % (
                        json.dumps(fn["name"], ensure_ascii=False), json.dumps(args, ensure_ascii=False))
                    f.write(json.dumps({"rendered": rendered, "name": fn["name"], "arguments": args}, ensure_ascii=False) + "\n")
                    n += 1
    print(f"tool_calls_all.jsonl: {n} calls")


if __name__ == "__main__":
    sys.exit(main())
