import json
import os
import sys
import time
import urllib.request
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

# Ensure utf-8 output in Windows terminal
sys.stdout.reconfigure(encoding="utf-8")

SAMPLES_DIR = Path("tools/hf_samples")
SAMPLES_DIR.mkdir(parents=True, exist_ok=True)

# 1. Fetch sample rows metadata from Hugging Face datasets-server
API_URL = "https://datasets-server.huggingface.co/rows?dataset=ujs%2Fhinglish&config=default&split=train&offset=0&limit=6"
print("Fetching metadata from Hugging Face...")
req = urllib.request.Request(API_URL, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))

rows = data["rows"]
print(f"Retrieved {len(rows)} samples.")

samples = []
for idx, item in enumerate(rows):
    row = item["row"]
    sentence = row.get("sentence", "")
    audio_info = row.get("audio", [{}])[0]
    audio_url = audio_info.get("src")
    sample_name = f"sample_{idx+1}.wav"
    wav_path = SAMPLES_DIR / sample_name
    
    if not wav_path.exists():
        print(f"Downloading {sample_name}...")
        req_audio = urllib.request.Request(audio_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req_audio) as a_resp:
            wav_bytes = a_resp.read()
        wav_path.write_bytes(wav_bytes)
    else:
        print(f"Sample {sample_name} already exists.")
        
    with wave.open(str(wav_path), "rb") as w:
        duration = w.getnframes() / w.getframerate()
        
    samples.append({
        "id": idx + 1,
        "name": sample_name,
        "path": wav_path,
        "duration": duration,
        "ground_truth": sentence,
    })

print(f"\nAll {len(samples)} samples ready.")
for s in samples:
    print(f"[{s['id']}] {s['duration']:.2f}s | Ground Truth: {s['ground_truth']}")
