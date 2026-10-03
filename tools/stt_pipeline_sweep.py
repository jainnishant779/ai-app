r"""Sweep the app's STT front-end settings (VAD threshold, silence, max segment, batch size, tail pad) with the app's
own pipeline reproduced in Python, so each number in SherpaOnnxStt.kt can be chosen by measurement.

Usage: .\\.venv\\Scripts\\python.exe tools\\stt_pipeline_sweep.py <out.txt>
Scores:
  meeting A/B   word error rate against the Qwen3-ASR transcript of the same excerpt (the most accurate model we have)
  noisy 68      words produced on a recording where both models hear only "Hello Vishal, how are you?"
  user clips    words and text, for reading side by side (no reference exists)
"""
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).parent))
from stt_ablation import MODELS, distance, words  # noqa: E402
from stt_eval import VAD, read_wav  # noqa: E402

SR = 16000
EVAL = Path(r"D:\nishant\toolchain\stt-eval")


def preprocess(x):
    """AudioPreprocessor.kt as it is now: 80 Hz high-pass, 7.5 kHz low-pass, one gain from active frames (75th pct)."""
    rc = 1 / (2 * np.pi * 80)
    a_hp = rc / (rc + 1 / SR)
    rc_lp = 1 / (2 * np.pi * 7500)
    a_lp = (1 / SR) / (rc_lp + 1 / SR)
    fr = x[: len(x) // 400 * 400].reshape(-1, 400)
    db = 10 * np.log10(np.mean(fr ** 2, axis=1) + 1e-12)
    active = db[db > -55]
    frames = active if len(active) >= len(db) / 10 else db
    gain = 10 ** (float(np.clip(-20 - np.percentile(frames, 75), -6, 18)) / 20)
    y = np.empty_like(x)
    px = py = lp = 0.0
    for i, v in enumerate(x):
        py = a_hp * (py + v - px)
        px = v
        lp = lp + a_lp * (py - lp)
        y[i] = lp
    return np.clip(y * gain, -0.98, 0.98).astype(np.float32)


def vad_segments(x, thr, sil, max_s):
    vc = sherpa_onnx.VadModelConfig()
    vc.silero_vad.model = str(VAD)
    vc.silero_vad.threshold = thr
    vc.silero_vad.min_silence_duration = sil
    vc.silero_vad.min_speech_duration = 0.25
    vc.silero_vad.max_speech_duration = max_s
    vc.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(vc, buffer_size_in_seconds=120)
    out = []
    for i in range(0, len(x), 512):
        vad.accept_waveform(x[i:i + 512])
        while not vad.empty():
            out.append((vad.front.start, vad.front.samples))
            vad.pop()
    vad.flush()
    while not vad.empty():
        out.append((vad.front.start, vad.front.samples))
        vad.pop()
    return out


def batches(segs, batch_s, tail_s, gap_s=2.0):
    """SpeechBatcher: join neighbours up to batch_s with 200 ms silence between; each segment gets tail_s of silence."""
    out, cur, size, last_end = [], [], 0, None
    gap = np.zeros(int(0.2 * SR), np.float32)
    for start, s in segs:
        s = np.concatenate([s, np.zeros(int(tail_s * SR), np.float32)])
        too_far = last_end is not None and (start - last_end) / SR > gap_s
        if cur and (too_far or size + len(gap) + len(s) > batch_s * SR):
            out.append(np.concatenate(cur))
            cur, size = [], 0
        if cur:
            cur.append(gap)
            size += len(gap)
        cur.append(s)
        size += len(s)
        last_end = start + len(s)
    if cur:
        out.append(np.concatenate(cur))
    # Whisper reads 30 s: split anything longer into equal parts (SpeechChunks)
    final = []
    for b in out:
        parts = -(-len(b) // (28 * SR))
        final += np.array_split(b, parts)
    return final


CONFIGS = {
    # name: (vad threshold, min silence, max speech s, batch s, tail pad s)
    "gemini (now)   thr.30 sil.45 max12 b10 tail.25": (0.30, 0.45, 12, 10, 0.25),
    "before         thr.50 sil.25 max28 b25 tail0":   (0.50, 0.25, 28, 25, 0.0),
    "thr.50 only    thr.50 sil.45 max12 b10 tail.25": (0.50, 0.45, 12, 10, 0.25),
    "thr.40         thr.40 sil.45 max12 b10 tail.25": (0.40, 0.45, 12, 10, 0.25),
    "long chunks    thr.50 sil.45 max20 b20 tail.25": (0.50, 0.45, 20, 20, 0.25),
    "no tail        thr.50 sil.45 max12 b10 tail0":   (0.50, 0.45, 12, 10, 0.0),
}


def main():
    out = Path(sys.argv[1])
    meeting = read_wav(EVAL / "meeting.wav")
    sets = {
        "meeting A": (meeting[600 * SR:780 * SR], EVAL / "qwen3_meeting_a.txt"),
        "meeting B": (meeting[1500 * SR:1680 * SR], EVAL / "qwen3_meeting_b.txt"),
        "noisy 68": (read_wav(Path(r"D:\nishant\ai-app\tools\68.wav")), None),
    }
    for n in ["10", "12", "15", "17"]:
        sets[f"user {n}"] = (read_wav(EVAL / "user-audio" / f"{n}.wav"), None)
    pre = {k: preprocess(v[0]) for k, v in sets.items()}
    refs = {k: words(v[1].read_text(encoding="utf-8").split("\n", 1)[1]) for k, v in sets.items() if v[1]}
    rec = MODELS["swift"]()
    lines = []
    for cname, (thr, sil, mx, b, tail) in CONFIGS.items():
        lines.append(f"## {cname}")
        t0 = time.time()
        for k, x in pre.items():
            chunks = batches(vad_segments(x, thr, sil, mx), b, tail)
            texts = []
            for c in chunks:
                st = rec.create_stream()
                st.accept_waveform(SR, c)
                rec.decode_stream(st)
                t = st.result.text.strip()
                if t:
                    texts.append(t)
            text = " ".join(texts)
            w = words(text)
            score = f"WER vs Qwen3 {distance(w, refs[k]):.2f}" if k in refs else f"{len(w)} words"
            lines.append(f"  {k:10s} {len(chunks):3d} calls  {score}   | {text[:160]}")
        lines.append(f"  ({time.time() - t0:.0f}s)")
        print("\n".join(lines[-len(pre) - 2:]), flush=True)
    out.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
