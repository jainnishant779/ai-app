import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
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

for sid in [20, 28, 5]:
    raw = read_wav(Path(f"tools/hf_samples/sample_{sid}.wav"))
    
    # 1. Without tail pad
    s1 = rec_swift.create_stream()
    s1.accept_waveform(16000, raw)
    rec_swift.decode_stream(s1)
    t1 = s1.result.text.strip()
    
    # 2. With 250ms tail silence pad
    tail = np.zeros(int(16000 * 0.25), dtype=np.float32)
    raw_padded = np.concatenate([raw, tail])
    s2 = rec_swift.create_stream()
    s2.accept_waveform(16000, raw_padded)
    rec_swift.decode_stream(s2)
    t2 = s2.result.text.strip()
    
    print(f"Sample {sid}:")
    print(f"  Raw:         \"{t1}\"")
    print(f"  With Tail:   \"{t2}\"")
