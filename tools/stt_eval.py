"""Compare STT model/language settings on real recordings (desktop, CPU).

Usage: .\\.venv\\Scripts\\python.exe tools\\stt_eval.py <wav-dir> [out.txt]
Uses the same pipeline as the app: Silero VAD segments -> offline whisper.
"""
import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

EVAL = Path(r"D:\nishant\toolchain\stt-eval")
TINY = Path(r"D:\nishant\llm_research\llama_bin\stt")
VAD = TINY / "silero_vad.onnx"


def whisper(dirpath: Path, prefix: str, lang: str, int8: bool = True):
    suffix = ".int8.onnx" if int8 else ".onnx"
    cfg = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(dirpath / f"{prefix}-encoder{suffix}"),
        decoder=str(dirpath / f"{prefix}-decoder{suffix}"),
        tokens=str(dirpath / f"{prefix}-tokens.txt"),
        language=lang, task="transcribe", num_threads=4,
    )
    return cfg


SWIFT = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")


def preprocess(x: np.ndarray) -> np.ndarray:
    """Same as the app's AudioPreprocessor: 80 Hz one-pole high-pass, then one gain (90th-percentile frame -> -20 dB)."""
    a = (1 / (2 * np.pi * 80)) / ((1 / (2 * np.pi * 80)) + 1 / 16000)
    y = np.empty_like(x)
    px = py = 0.0
    for i, v in enumerate(x):
        py = a * (py + v - px)
        px = v
        y[i] = py
    fr = x[: len(x) // 400 * 400].reshape(-1, 400)
    db = 10 * np.log10(np.mean(fr ** 2, axis=1) + 1e-12)
    gain_db = float(np.clip(-20 - np.percentile(db, 90), -6, 18))
    return np.clip(y * 10 ** (gain_db / 20), -0.98, 0.98).astype(np.float32)


CONFIGS = {
    "hinglish-swift lang=en": lambda: whisper(SWIFT, "hinglish-swift", "en"),
    "hinglish-swift lang=en +filter": lambda: whisper(SWIFT, "hinglish-swift", "en"),
    "hinglish-swift lang=hi": lambda: whisper(SWIFT, "hinglish-swift", "hi"),
    "tiny.en (current)": lambda: whisper(TINY / "sherpa-onnx-whisper-tiny.en", "tiny.en", "en"),
    "base  lang=hi": lambda: whisper(EVAL / "sherpa-onnx-whisper-base", "base", "hi"),
    "base  lang=en": lambda: whisper(EVAL / "sherpa-onnx-whisper-base", "base", "en"),
    "small lang=hi": lambda: whisper(EVAL / "sherpa-onnx-whisper-small", "small", "hi"),
    "small lang=en": lambda: whisper(EVAL / "sherpa-onnx-whisper-small", "small", "en"),
    "small lang=auto": lambda: whisper(EVAL / "sherpa-onnx-whisper-small", "small", ""),
}


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def segments(samples: np.ndarray):
    vc = sherpa_onnx.VadModelConfig()
    vc.silero_vad.model = str(VAD)
    vc.silero_vad.threshold = 0.5
    vc.silero_vad.min_silence_duration = 0.25
    vc.silero_vad.min_speech_duration = 0.25
    vc.silero_vad.max_speech_duration = 28
    vc.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vc, buffer_size_in_seconds=60)
    out = []
    win = vc.silero_vad.window_size
    for i in range(0, len(samples), win):
        vad.accept_waveform(samples[i:i + win])
        while not vad.empty():
            out.append(vad.front.samples)
            vad.pop()
    vad.flush()
    while not vad.empty():
        out.append(vad.front.samples)
        vad.pop()
    return out


def transcribe(rec, segs):
    texts = []
    for s in segs:
        st = rec.create_stream()
        st.accept_waveform(16000, s)
        rec.decode_stream(st)
        t = st.result.text.strip()
        if t:
            texts.append(t)
    return " ".join(texts)


def main():
    wav_dir = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    wavs = sorted(wav_dir.glob("*.wav"), key=lambda p: int(p.stem))
    wavs = [w for w in wavs if (w.stat().st_size - 44) / 32000 >= 2.5]  # skip near-empty clips
    audio = {w.name: read_wav(w) for w in wavs}
    segs = {n: segments(a) for n, a in audio.items()}
    filtered = {n: preprocess(a) for n, a in audio.items()}
    seg_f = {n: segments(a) for n, a in filtered.items()}
    only = sys.argv[3:] if len(sys.argv) > 3 else None  # optional: config-name substrings to run
    lines = []
    for name, make in CONFIGS.items():
        if only and not any(o in name for o in only):
            continue
        use_filter = "+filter" in name
        if use_filter:
            segs_used = seg_f
        else:
            segs_used = segs
        t0 = time.time()
        try:
            rec = make()
        except Exception as e:  # model not downloaded yet
            lines.append(f"## {name}: SKIPPED ({e})")
            continue
        total_audio = sum(len(a) for a in audio.values()) / 16000
        lines.append(f"## {name}")
        for n in audio:
            lines.append(f"  [{n}] {transcribe(rec, segs_used[n])}")
        lines.append(f"  (decode {time.time() - t0:.1f}s for {total_audio:.0f}s audio)")
        lines.append("")
    text = "\n".join(lines)
    print(text)
    if out:
        out.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
