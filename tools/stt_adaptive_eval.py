r"""Short fixed chunks versus "long chunk, re-read short when it looks truncated", on both kinds of audio we have:
the English meeting (WER against Qwen3-ASR) and the user's Hinglish phone recordings (total words; whisper stopping
early shows up as missing words).

Usage: .\.venv\Scripts\python.exe tools\stt_adaptive_eval.py <phone-wav-dir>
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from stt_ablation import MODELS, distance, words  # noqa: E402
from stt_eval import read_wav  # noqa: E402
from stt_pipeline_sweep import EVAL, SR, preprocess, vad_segments  # noqa: E402

TAIL = 0.25


def groups(segs, batch_s, gap_s=2.0):
    """Lists of VAD segments that the app's batcher would join into one call (same speaker assumed)."""
    out, cur, size, last_end = [], [], 0, None
    for start, s in segs:
        n = len(s) + int(TAIL * SR) + (int(0.2 * SR) if cur else 0)
        too_far = last_end is not None and (start - last_end) / SR > gap_s
        if cur and (too_far or size + n > batch_s * SR):
            out.append(cur)
            cur, size = [], 0
            n = len(s) + int(TAIL * SR)
        cur.append((start, s))
        size += n
        last_end = start + len(s)
    if cur:
        out.append(cur)
    return out


def audio(group):
    gap = np.zeros(int(0.2 * SR), np.float32)
    parts = []
    for i, (_, s) in enumerate(group):
        if i:
            parts.append(gap)
        parts.append(s)
        parts.append(np.zeros(int(TAIL * SR), np.float32))
    return np.concatenate(parts)


def decode(rec, x):
    st = rec.create_stream()
    st.accept_waveform(SR, x)
    rec.decode_stream(st)
    return st.result.text.strip()


def run(rec, x, mode):
    if mode.startswith("fixed"):
        n = int(mode.split()[1])
        return " ".join(decode(rec, audio(g)) for g in groups(vad_segments(x, 0.30, 0.45, n), n))
    # adaptive: long call first; if it reads fewer than MIN_WPS words per second of speech, re-read in 8 s pieces
    min_wps = float(mode.split()[1])
    texts = []
    for g in groups(vad_segments(x, 0.30, 0.45, 27), 25):
        long = decode(rec, audio(g))
        speech_s = sum(len(s) for _, s in g) / SR
        if speech_s >= 6 and len(words(long)) / speech_s < min_wps:
            short = []
            for _, s in g:  # each VAD segment, cut to <= 8 s
                for i in range(0, len(s), 8 * SR):
                    piece = s[i:i + 8 * SR]
                    if len(piece) > SR // 4:
                        short.append(decode(rec, np.concatenate([piece, np.zeros(int(TAIL * SR), np.float32)])))
            alt = " ".join(t for t in short if t)
            if len(words(alt)) > len(words(long)):
                long = alt
        texts.append(long)
    return " ".join(t for t in texts if t)


def main():
    rec = MODELS["swift"]()
    meeting = read_wav(EVAL / "meeting.wav")
    meet = {
        "A": (preprocess(meeting[600 * SR:780 * SR]), words((EVAL / "qwen3_meeting_a.txt").read_text(encoding="utf-8").split("\n", 1)[1])),
        "B": (preprocess(meeting[1500 * SR:1680 * SR]), words((EVAL / "qwen3_meeting_b.txt").read_text(encoding="utf-8").split("\n", 1)[1])),
    }
    phone = {w.stem: preprocess(read_wav(w)) for w in sorted(Path(sys.argv[1]).glob("*.wav"), key=lambda p: int(p.stem))}
    for mode in ["fixed 8", "fixed 6", "adaptive 1.2", "adaptive 1.6", "adaptive 2.0"]:
        wer = {k: distance(words(run(rec, x, mode)), ref) for k, (x, ref) in meet.items()}
        per = {k: len(words(run(rec, x, mode))) for k, x in phone.items()}
        print(f"{mode:13s} meeting WER A {wer['A']:.2f} B {wer['B']:.2f} | phone words {sum(per.values())} "
              f"(39:{per.get('39')} 42:{per.get('42')} 51:{per.get('51')} 65:{per.get('65')} 75:{per.get('75')})", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
