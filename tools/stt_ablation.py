"""Which front-end step helps or hurts the transcript? Runs one excerpt through variants that differ in one thing each.

Usage: .\\.venv\\Scripts\\python.exe tools\\stt_ablation.py <wav> <start_s> <duration_s> <out.txt> [model ...]
Models (default: swift): swift, small-en, base-en. Variants:
  raw+vad          no filter, Silero VAD segments
  gain+vad         gain only (no high-pass)
  app+vad          80 Hz high-pass + gain, what the app does today
  raw+fixed        no filter, no VAD: fixed 25 s windows (nothing is ever discarded)
  app+fixed        app filter, fixed 25 s windows
  app+vad-loose    app filter, VAD with lower threshold and longer tail (keeps more soft speech)
There is no ground truth for the meeting, so the report also prints each variant's word-level distance to the
longest-model transcript (small-en, app+fixed) as a rough reference.
"""
import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).parent))
from stt_eval import EVAL, SWIFT, TINY, VAD, preprocess, read_wav, whisper  # noqa: E402

MODELS = {
    "swift": lambda: whisper(SWIFT, "hinglish-swift", "en"),
    "small-en": lambda: whisper(EVAL / "sherpa-onnx-whisper-small", "small", "en"),
    "base-en": lambda: whisper(EVAL / "sherpa-onnx-whisper-base", "base", "en"),
}


def gain_only(x: np.ndarray) -> np.ndarray:
    fr = x[: len(x) // 400 * 400].reshape(-1, 400)
    db = 10 * np.log10(np.mean(fr ** 2, axis=1) + 1e-12)
    gain_db = float(np.clip(-20 - np.percentile(db, 90), -6, 18))
    return np.clip(x * 10 ** (gain_db / 20), -0.98, 0.98).astype(np.float32)


def vad_segments(x: np.ndarray, threshold=0.5, min_silence=0.25, min_speech=0.25):
    vc = sherpa_onnx.VadModelConfig()
    vc.silero_vad.model = str(VAD)
    vc.silero_vad.threshold = threshold
    vc.silero_vad.min_silence_duration = min_silence
    vc.silero_vad.min_speech_duration = min_speech
    vc.silero_vad.max_speech_duration = 28
    vc.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vc, buffer_size_in_seconds=60)
    win = vc.silero_vad.window_size
    out = []
    for i in range(0, len(x), win):
        vad.accept_waveform(x[i:i + win])
        while not vad.empty():
            out.append(vad.front.samples)
            vad.pop()
    vad.flush()
    while not vad.empty():
        out.append(vad.front.samples)
        vad.pop()
    return out


def fixed_windows(x: np.ndarray, seconds=25):
    n = seconds * 16000
    return [x[i:i + n] for i in range(0, len(x), n) if len(x[i:i + n]) > 4000]


def batch(segs, max_s=25, gap_s=0.2):
    """Same idea as the app's SpeechBatcher, without speaker or gap info: join neighbours up to max_s."""
    out, cur = [], []
    size = 0
    for s in segs:
        add = len(s) + (int(gap_s * 16000) if cur else 0)
        if cur and size + add > max_s * 16000:
            out.append(np.concatenate(cur))
            cur, size, add = [], 0, len(s)
        if cur:
            cur.append(np.zeros(int(gap_s * 16000), dtype=np.float32))
        cur.append(s)
        size += add
    if cur:
        out.append(np.concatenate(cur))
    return out


def decode(rec, segs):
    texts = []
    for s in segs:
        st = rec.create_stream()
        st.accept_waveform(16000, s)
        rec.decode_stream(st)
        t = st.result.text.strip()
        if t:
            texts.append(t)
    return texts


def words(t: str):
    return "".join(c.lower() if c.isalnum() or c == " " else " " for c in t).split()


def distance(a, b):
    """Word-level edit distance divided by the reference length."""
    d = list(range(len(b) + 1))
    for i, wa in enumerate(a, 1):
        prev, d[0] = d[0], i
        for j, wb in enumerate(b, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (wa != wb))
            prev, d[j] = d[j], cur
    return d[-1] / max(1, len(b))


FRAGMENT = {"haan", "aam", "ve", "dh", "hara", "ha", "hmm", "um", "uh"}


def main():
    wav, start, dur, out = Path(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]), Path(sys.argv[4])
    models = sys.argv[5:] or ["swift"]
    x = read_wav(wav)[int(start * 16000):int((start + dur) * 16000)]
    filt, gain = preprocess(x), gain_only(x)
    variants = {
        "raw+vad": lambda: batch(vad_segments(x)),
        "gain+vad": lambda: batch(vad_segments(gain)),
        "app+vad": lambda: batch(vad_segments(filt)),
        "raw+fixed": lambda: fixed_windows(x),
        "app+fixed": lambda: fixed_windows(filt),
        "app+vad-loose": lambda: batch(vad_segments(filt, threshold=0.35, min_silence=0.6, min_speech=0.2)),
    }
    plan = {n: v() for n, v in variants.items()}
    lines = [f"# {wav.name} {start:.0f}-{start + dur:.0f} s   audio {len(x) / 16000:.0f} s",
             "# chunks sent to the recognizer and speech kept (seconds): " +
             ", ".join(f"{n}: {len(s)} / {sum(len(a) for a in s) / 16000:.0f}" for n, s in plan.items()), ""]
    results = {}
    for m in models:
        rec = MODELS[m]()
        for n, segs in plan.items():
            t0 = time.time()
            texts = decode(rec, segs)
            results[(m, n)] = " ".join(texts)
            w = words(results[(m, n)])
            frag = sum(1 for t in texts if len(words(t)) <= 2)
            lines.append(f"## {m} / {n}: {len(w)} words, {frag} chunks of <=2 words, "
                         f"{sum(1 for a in w if a in FRAGMENT)} filler tokens, {time.time() - t0:.0f}s")
            lines.append(results[(m, n)])
            lines.append("")
    ref_key = ("small-en", "app+fixed")
    if ref_key in results:
        ref = words(results[ref_key])
        lines.append(f"## distance to reference {ref_key} (lower = closer; reference is itself imperfect)")
        for (m, n), t in results.items():
            lines.append(f"  {m:9s} {n:14s} {distance(words(t), ref):.2f}")
    out.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(l for l in lines if l.startswith(("#", "  "))))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
