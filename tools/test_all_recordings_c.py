import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx
import re

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def high_pass_80(samples: np.ndarray, fs: int = 16000) -> np.ndarray:
    rc = 1.0 / (2.0 * np.pi * 80.0)
    dt = 1.0 / fs
    a = rc / (rc + dt)
    out = np.zeros_like(samples)
    prev_x, prev_y = 0.0, 0.0
    for i in range(len(samples)):
        x = float(samples[i])
        y = a * (prev_y + x - prev_x)
        prev_x = x
        prev_y = y
        out[i] = y
    return out

def low_pass_7500(samples: np.ndarray, fs: int = 16000) -> np.ndarray:
    rc = 1.0 / (2.0 * np.pi * 7500.0)
    dt = 1.0 / fs
    a = dt / (rc + dt)
    out = np.zeros_like(samples)
    prev_y = 0.0
    for i in range(len(samples)):
        y = prev_y + a * (float(samples[i]) - prev_y)
        prev_y = y
        out[i] = y
    return out

def normalize_gain(samples: np.ndarray, target_db: float = -20.0) -> np.ndarray:
    frame_len = 400
    levels = []
    for i in range(0, len(samples) - frame_len + 1, frame_len):
        rms = np.sqrt(np.mean(samples[i:i+frame_len]**2) + 1e-12)
        db = 20.0 * np.log10(rms)
        if db > -55.0:
            levels.append(db)
    if not levels:
        levels = [-40.0]
    speech_db = np.percentile(levels, 75)
    gain_db = np.clip(target_db - speech_db, -6.0, 18.0)
    gain = 10.0 ** (gain_db / 20.0)
    return np.clip(samples * gain, -0.98, 0.98)

def run_vad_segments(samples: np.ndarray, thresh: float = 0.30):
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

spoken_email = re.compile(r"(\w+)\s+at\s+the\s+rate\s+(\w+)\s+dot\s+(com|in|org|net|co|io)\b", re.IGNORECASE)
def clean_text(text: str) -> str:
    t = spoken_email.sub(r"\1@\2.\3", text)
    # Common name corrections
    t = re.sub(r"\b(nisanjian|nishaanjan|neesan jain|nissan jain|nishaan jain)\b", "Nishant Jain", t, flags=re.IGNORECASE)
    return t

for fname in ["38.wav", "39.wav", "40.wav", "41.wav", "42.wav", "43.wav"]:
    p = Path(f"tools/{fname}")
    if not p.exists(): continue
    raw = read_wav(p)
    processed = normalize_gain(low_pass_7500(high_pass_80(raw)))
    segs = run_vad_segments(processed, thresh=0.30)
    print(f"\n==================================================", flush=True)
    print(f"FILE: {fname} (Length: {len(raw)/16000:.1f}s, Segments: {len(segs)})", flush=True)
    for idx, (st, arr) in enumerate(segs):
        t0 = st / 16000.0
        # Split chunks max 10s
        for k in range(0, len(arr), 10 * 16000):
            sub = arr[k:k+10*16000]
            if len(sub) < 4000: continue
            sub_t0 = t0 + k / 16000.0
            sub_t1 = sub_t0 + len(sub) / 16000.0
            s = rec_swift.create_stream()
            s.accept_waveform(16000, sub)
            rec_swift.decode_stream(s)
            txt = clean_text(s.result.text.strip())
            print(f"  [{sub_t0:5.2f}s - {sub_t1:5.2f}s] \"{txt}\"", flush=True)
