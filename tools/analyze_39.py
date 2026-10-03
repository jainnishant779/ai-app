import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
QWEN_DIR = Path(r"D:\nishant\toolchain\stt-eval\qwen3-hinglish")

with wave.open("tools/39.wav", "rb") as w:
    samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

# Boost quiet audio dynamically (per-window normalization)
def normalize_audio(audio, frame_len=16000*2):
    out = audio.copy()
    for i in range(0, len(out), frame_len):
        chunk = out[i:i+frame_len]
        peak = np.max(np.abs(chunk))
        if peak > 0.005:
            gain = min(5.0, 0.7 / peak)
            out[i:i+frame_len] = chunk * gain
    return np.clip(out, -0.98, 0.98)

norm_samples = normalize_audio(samples)

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)
rec_qwen = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
    conv_frontend=str(QWEN_DIR / "conv_frontend.onnx"),
    encoder=str(QWEN_DIR / "encoder.int8.onnx"),
    decoder=str(QWEN_DIR / "decoder.int8.onnx"),
    tokenizer=str(QWEN_DIR / "tokenizer"),
    num_threads=4, max_new_tokens=256, max_total_len=1024,
)

print(f"Total duration: {len(samples)/16000:.2f}s\n", flush=True)

# Slices of 10 seconds
step = 10 * 16000
for i in range(0, len(samples), step):
    chunk = norm_samples[i:i+step]
    if len(chunk) < 8000:
        continue
    t0 = i / 16000.0
    t1 = (i + len(chunk)) / 16000.0
    
    st1 = rec_swift.create_stream()
    st1.accept_waveform(16000, chunk)
    rec_swift.decode_stream(st1)
    
    st2 = rec_qwen.create_stream()
    st2.accept_waveform(16000, chunk)
    rec_qwen.decode_stream(st2)
    
    print(f"[{t0:.1f}s - {t1:.1f}s]:", flush=True)
    print(f"   Swift (Roman):  {st1.result.text.strip()}", flush=True)
    print(f"   Qwen3 (Hinglish): {st2.result.text.strip()}", flush=True)
