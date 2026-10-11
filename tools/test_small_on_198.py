import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def main():
    wav_path = Path("tools/198.wav")
    audio = read_wav(wav_path)
    print(f"Audio: {wav_path} (Duration: {len(audio)/16000:.2f}s)")
    
    model_dir = Path("tools/models/whisper-small")
    
    print("\n--- Testing Whisper Small (Multilingual / Auto) ---")
    rec_auto = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(model_dir / "small-encoder.int8.onnx"),
        decoder=str(model_dir / "small-decoder.int8.onnx"),
        tokens=str(model_dir / "small-tokens.txt"),
        language="",
        task="transcribe",
        num_threads=4,
    )
    s = rec_auto.create_stream()
    s.accept_waveform(16000, audio)
    rec_auto.decode_stream(s)
    print(f"Whisper Small (Auto):\n  {s.result.text}\n")
    
    print("\n--- Testing Whisper Small (lang='en') ---")
    rec_en = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(model_dir / "small-encoder.int8.onnx"),
        decoder=str(model_dir / "small-decoder.int8.onnx"),
        tokens=str(model_dir / "small-tokens.txt"),
        language="en",
        task="transcribe",
        num_threads=4,
    )
    s_en = rec_en.create_stream()
    s_en.accept_waveform(16000, audio)
    rec_en.decode_stream(s_en)
    print(f"Whisper Small (en):\n  {s_en.result.text}\n")

    print("\n--- Testing Whisper Small (lang='hi') ---")
    rec_hi = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(model_dir / "small-encoder.int8.onnx"),
        decoder=str(model_dir / "small-decoder.int8.onnx"),
        tokens=str(model_dir / "small-tokens.txt"),
        language="hi",
        task="transcribe",
        num_threads=4,
    )
    s_hi = rec_hi.create_stream()
    s_hi.accept_waveform(16000, audio)
    rec_hi.decode_stream(s_hi)
    print(f"Whisper Small (hi):\n  {s_hi.result.text}\n")

if __name__ == "__main__":
    main()
