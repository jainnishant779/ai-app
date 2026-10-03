import wave, numpy as np, sherpa_onnx
from pathlib import Path

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

with wave.open("tools/39.wav", "rb") as w:
    raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

sub = raw[int(16.74*16000):int(39.39*16000)]
print("Sub len:", len(sub)/16000.0)

step = int(6.0 * 16000)
for t in range(0, len(sub), step):
    c = sub[t:t+step]
    if len(c) < 8000: continue
    rms = np.sqrt(np.mean(c**2))
    gain = min(5.0, 0.08 / (rms + 1e-6))
    boosted = np.clip(c * gain, -0.98, 0.98)
    
    s = rec_swift.create_stream()
    s.accept_waveform(16000, boosted)
    rec_swift.decode_stream(s)
    t_start = 16.74 + t / 16000.0
    t_end = 16.74 + (t + len(c)) / 16000.0
    print(f"[{t_start:.2f}s - {t_end:.2f}s]: Swift: \"{s.result.text.strip()}\"")
