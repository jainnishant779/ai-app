import json
import sys
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

CACHE_FILE = Path("tools/hf_samples/metadata.json")

def fetch_metadata(limit=50):
    if CACHE_FILE.exists():
        print(f"Loading cached metadata from {CACHE_FILE}...")
        return json.loads(CACHE_FILE.read_text(encoding="utf-8"))
    
    print(f"Fetching metadata for {limit} rows from Hugging Face...")
    url = f"https://datasets-server.huggingface.co/rows?dataset=ujs%2Fhinglish&config=default&split=train&offset=0&limit={limit}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    
    metadata = {}
    for idx, item in enumerate(data.get("rows", [])):
        row = item["row"]
        metadata[idx + 1] = {
            "sentence": row.get("sentence", ""),
            "id": row.get("id", idx + 1),
        }
    
    CACHE_FILE.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved metadata for {len(metadata)} samples to {CACHE_FILE}.")
    return metadata

if __name__ == "__main__":
    meta = fetch_metadata(30)
    for sample_id in range(1, 11):
        info = meta.get(str(sample_id)) or meta.get(sample_id, {})
        print(f"Sample {sample_id}: {info.get('sentence')}")
