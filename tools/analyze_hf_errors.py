import json
import re
import sys
import time
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")
SAMPLES_DIR = Path("tools/hf_samples")
META_FILE = SAMPLES_DIR / "metadata.json"

metadata = json.loads(META_FILE.read_text(encoding="utf-8"))

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

# Replicate App's AudioPreprocessor
def app_preprocess(samples: np.ndarray, high_pass_hz=80.0, low_pass_hz=7500.0, target_db=-20.0, fs=16000) -> np.ndarray:
    # 80 Hz High-pass
    rc_hp = 1.0 / (2.0 * np.pi * high_pass_hz)
    dt = 1.0 / fs
    a_hp = rc_hp / (rc_hp + dt)
    hp_out = np.zeros_like(samples)
    prev_x, prev_y = 0.0, 0.0
    for i in range(len(samples)):
        x = float(samples[i])
        y = a_hp * (prev_y + x - prev_x)
        prev_x = x
        prev_y = y
        hp_out[i] = y

    # 7.5 kHz Low-pass
    rc_lp = 1.0 / (2.0 * np.pi * low_pass_hz)
    a_lp = dt / (rc_lp + dt)
    lp_out = np.zeros_like(hp_out)
    prev_y = 0.0
    for i in range(len(hp_out)):
        y = prev_y + a_lp * (float(hp_out[i]) - prev_y)
        prev_y = y
        lp_out[i] = y

    # Gain normalization to -20 dB FS
    frame_len = 400
    levels = []
    for i in range(0, len(lp_out) - frame_len + 1, frame_len):
        rms = np.sqrt(np.mean(lp_out[i:i+frame_len]**2) + 1e-12)
        db = 20.0 * np.log10(rms)
        if db > -55.0:
            levels.append(db)
    if not levels:
        levels = [-40.0]
    speech_db = np.percentile(levels, 75)
    gain_db = np.clip(target_db - speech_db, -6.0, 18.0)
    gain = 10.0 ** (gain_db / 20.0)
    return np.clip(lp_out * gain, -0.98, 0.98)

# Replicate App's VAD
def run_app_vad(samples: np.ndarray, thresh=0.30):
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(VAD_MODEL)
    vcfg.silero_vad.threshold = thresh
    vcfg.silero_vad.min_silence_duration = 0.30
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
    
    for i in range(0, len(samples), 512):
        chunk = samples[i:i+512]
        if len(chunk) < 512:
            chunk = np.pad(chunk, (0, 512 - len(chunk)))
        vad.accept_waveform(chunk)
    vad.flush()
    segs = []
    while not vad.empty():
        s = vad.front
        arr = np.array(s.samples, dtype=np.float32)
        segs.append((s.start, arr))
        vad.pop()
    return segs

print("Loading models for comparison...", flush=True)
rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)
rec_base_hi = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="hi", task="transcribe", num_threads=4,
)

# Test a broad set of sample IDs
test_ids = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 28, 52, 91]

print(f"\n=======================================================", flush=True)
print(f"BENCHMARKING APP PIPELINE ON {len(test_ids)} HUGGING FACE HINGLISH SAMPLES", flush=True)
print(f"=======================================================", flush=True)

results = []
for sid in test_ids:
    wav_file = SAMPLES_DIR / f"sample_{sid}.wav"
    if not wav_file.exists(): continue
    raw = read_wav(wav_file)
    dur = len(raw) / 16000.0
    gt = (metadata.get(str(sid)) or metadata.get(sid, {})).get("sentence", "")
    
    # App Pipeline: Preprocess -> VAD -> Transcribe
    proc = app_preprocess(raw)
    vad_segs = run_app_vad(proc, thresh=0.30)
    
    # Transcribe with Hinglish Swift (App's default Hinglish model)
    swift_texts = []
    for st, arr in vad_segs:
        # Prepend 320ms silence on first segment like app
        s = rec_swift.create_stream()
        s.accept_waveform(16000, arr)
        rec_swift.decode_stream(s)
        t = s.result.text.strip()
        if t: swift_texts.append(t)
    app_swift_out = " ".join(swift_texts) if swift_texts else "[NO SPEECH DETECTED]"
    
    # Also transcribe whole with Base (Hindi) for script comparison
    s_hi = rec_base_hi.create_stream()
    s_hi.accept_waveform(16000, raw)
    rec_base_hi.decode_stream(s_hi)
    base_hi_out = s_hi.result.text.strip()
    
    results.append({
        "id": sid,
        "dur": dur,
        "gt": gt,
        "app_swift": app_swift_out,
        "base_hi": base_hi_out,
        "vad_segments": len(vad_segs),
    })

# Print detailed results
for r in results:
    print(f"\n--- Sample #{r['id']} ({r['dur']:.2f}s, VAD segs: {r['vad_segments']}) ---", flush=True)
    print(f"  GROUND TRUTH: \"{r['gt']}\"")
    print(f"  APP (SWIFT):  \"{r['app_swift']}\"")
    print(f"  BASE (HI):    \"{r['base_hi']}\"")

# Save to JSON for report generation
Path("tools/hf_eval_results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
print("\nResults saved to tools/hf_eval_results.json", flush=True)
