import os
import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
QWEN_DIR = Path(r"D:\nishant\toolchain\stt-eval\qwen3-hinglish")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def eval_whisper(model_dir: Path, prefix: str, lang: str, audio: np.ndarray, name: str):
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(model_dir / f"{prefix}-encoder.int8.onnx"),
        decoder=str(model_dir / f"{prefix}-decoder.int8.onnx"),
        tokens=str(model_dir / f"{prefix}-tokens.txt"),
        language=lang,
        task="transcribe",
        num_threads=4,
    )
    s = recognizer.create_stream()
    s.accept_waveform(16000, audio)
    recognizer.decode_stream(s)
    text = s.result.text
    print(f"[{name} - lang='{lang}']:\n  {text}\n")

def eval_qwen(audio: np.ndarray, name: str):
    recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=str(QWEN_DIR / "conv_frontend.onnx"),
        encoder=str(QWEN_DIR / "encoder.int8.onnx"),
        decoder=str(QWEN_DIR / "decoder.int8.onnx"),
        tokenizer=str(QWEN_DIR / "tokenizer"),
        num_threads=4,
        max_total_len=1024,
        max_new_tokens=448,
    )
    s = recognizer.create_stream()
    s.accept_waveform(16000, audio)
    recognizer.decode_stream(s)
    text = s.result.text
    print(f"[Qwen3-ASR]:\n  {text}\n")

if __name__ == "__main__":
    for wav_name in ["38.wav", "39.wav"]:
        print(f"================== {wav_name} ==================")
        audio = read_wav(Path(f"tools/{wav_name}"))
        print(f"Duration: {len(audio)/16000:.2f}s")
        
        # Test Swift
        eval_whisper(SWIFT_DIR, "hinglish-swift", "en", audio, "Swift")
        
        # Test Base in Hindi & English
        eval_whisper(BASE_DIR, "base", "hi", audio, "Whisper Base")
        eval_whisper(BASE_DIR, "base", "en", audio, "Whisper Base")
        
        # Test Qwen3
        eval_qwen(audio, "Qwen3")
