"""Extract the canonical Nishu system prompt from train.jsonl line 1 and write it as LF-only bytes.

Never reads sys_test.txt (CRLF copy, wrong bytes). Fails loudly if length or sha256 drift.
"""
import hashlib
import json
import sys
from pathlib import Path

TRAIN = Path(r"D:\nishant\llm_research\data\v2\final\train.jsonl")
OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "system_prompt.bin"
EXPECTED_LEN = 1024
EXPECTED_SHA = "0d30a62b8de680791d8acbc8a14e5d62df0d0cca2c4474a3ae63a98aead6f3c3"


def main() -> int:
    with TRAIN.open("rb") as f:
        first = json.loads(f.readline())
    data = first["messages"][0]["content"].encode("utf-8")
    sha = hashlib.sha256(data).hexdigest()
    if len(data) != EXPECTED_LEN or b"\r" in data or sha != EXPECTED_SHA:
        print(f"FAIL: len={len(data)} CR={data.count(b'\\r')} sha256={sha}", file=sys.stderr)
        return 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"OK: wrote {OUT} len={len(data)} sha256={sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
