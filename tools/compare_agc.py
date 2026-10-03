import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

with wave.open("tools/39.wav", "rb") as w:
    raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

# 1. Test raw
print("=== RAW AUDIO SLICES (as captured) ===", flush=True)
for start, end in [(0, 10), (10, 20), (20, 30), (30, 40), (40, 50), (50, 60), (60, 85)]:
    chunk = raw[start*16000:end*16000]
    st = rec_swift.create_stream()
    st.accept_waveform(16000, chunk)
    rec_swift.decode_stream(st)
    print(f"[{start}s - {end}s]: {st.result.text.strip()}", flush=True)

# 2. Test Dynamic Windowed AGC (boost quiet chunks)
print("\n=== DYNAMIC AGC NORMALIZED AUDIO SLICES ===", flush=True)
def apply_dynamic_agc(audio, window_sec=3.0, target_rms=0.08):
    win_len = int(window_sec * 16000)
    out = np.zeros_like(audio)
    for i in range(0, len(audio), win_len):
        chunk = audio[i:i+win_len]
        rms = np.sqrt(np.mean(chunk**2))
        if rms > 0.003: # if speech/noise above silence floor
            gain = min(8.0, target_rms / rms)
        else:
            gain = 1.0
        out[i:i+win_len] = np.clip(chunk * gain, -0.98, 0.98)
    return out

agc_audio = apply_dynamic_agc(raw)
for start, end in [(0, 10), (10, 20), (20, 30), (30, 40), (40, 50), (50, 60), (60, 85)]:
    chunk = agc_audio[start*16000:end*16000]
    st = rec_swift.create_stream()
    st.accept_waveform(16000, chunk)
    rec_swift.decode_stream(st)
    print(f"[{start}s - {end}s]: {st.result.text.strip()}", flush=True)
