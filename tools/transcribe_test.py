import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")

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
rec_base_en = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

for name, gain in [("38.wav", 4.0), ("39.wav", 2.0)]:
    print(f"\n=======================================================", flush=True)
    print(f"=== {name} (gain {gain}x) ===", flush=True)
    with wave.open(f"tools/{name}", "rb") as w:
        samples = (np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0) * gain
        samples = np.clip(samples, -0.98, 0.98)
    
    # decode in 20s windows
    chunk_len = 20 * 16000
    for i in range(0, len(samples), chunk_len):
        chunk = samples[i:i+chunk_len]
        if len(chunk) < 8000:
            continue
        
        t0 = i / 16000.0
        t1 = (i + len(chunk)) / 16000.0
        print(f"\n--- [{t0:.1f}s - {t1:.1f}s] ---", flush=True)
        
        st = rec_swift.create_stream()
        st.accept_waveform(16000, chunk)
        rec_swift.decode_stream(st)
        print(f"  Swift (Roman Hinglish):  {st.result.text.strip()}", flush=True)
        
        st2 = rec_base_hi.create_stream()
        st2.accept_waveform(16000, chunk)
        rec_base_hi.decode_stream(st2)
        print(f"  Base (Hindi Devanagari): {st2.result.text.strip()}", flush=True)
        
        st3 = rec_base_en.create_stream()
        st3.accept_waveform(16000, chunk)
        rec_base_en.decode_stream(st3)
        print(f"  Base (English Mode):     {st3.result.text.strip()}", flush=True)
