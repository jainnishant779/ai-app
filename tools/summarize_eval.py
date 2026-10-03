import json
import re
from pathlib import Path

results = json.loads(Path("d:/nishant/ai-app/tools/hf_eval_results.json").read_text(encoding="utf-8"))

def normalize(text: str) -> list[str]:
    t = text.lower()
    t = re.sub(r"[^\w\s]", " ", t)
    return [w for w in t.split() if w]

print(f"Total samples analyzed: {len(results)}\n")
for r in results:
    print(f"Sample #{r['id']} ({r['dur']:.1f}s):")
    print(f"  GT:    {r['gt']}")
    print(f"  Swift: {r['app_swift']}")
    print(f"  Base:  {r['base_hi']}")
    print()
