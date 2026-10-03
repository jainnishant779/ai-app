r"""Does a longer recognizer call lose words on the user's own Hinglish recordings? Whisper can stop early (end of
text) on a long chunk with pauses. Counts words per config on every WAV in a folder; fewer words = speech lost.

Usage: .\.venv\Scripts\python.exe tools\stt_chunk_sweep.py <wav-dir> <out.txt>
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from stt_ablation import MODELS, words  # noqa: E402
from stt_eval import read_wav  # noqa: E402
from stt_pipeline_sweep import SR, batches, preprocess, vad_segments  # noqa: E402

CONFIGS = {  # (threshold, min silence, max speech, batch, tail)
    "12s/10s (gemini)": (0.30, 0.45, 12, 10, 0.25),
    "15s/15s": (0.30, 0.45, 15, 15, 0.25),
    "20s/20s": (0.30, 0.45, 20, 20, 0.25),
    "27s/25s": (0.30, 0.45, 27, 25, 0.25),
    "8s/8s": (0.30, 0.45, 8, 8, 0.25),
}


def main():
    wavs = sorted(Path(sys.argv[1]).glob("*.wav"), key=lambda p: int(p.stem))
    rec = MODELS["swift"]()
    pre = {w.stem: preprocess(read_wav(w)) for w in wavs}
    lines, totals = [], {c: 0 for c in CONFIGS}
    for name, x in pre.items():
        lines.append(f"## {name}.wav ({len(x) / SR:.0f}s)")
        for c, (thr, sil, mx, b, tail) in CONFIGS.items():
            texts = []
            for chunk in batches(vad_segments(x, thr, sil, mx), b, tail):
                st = rec.create_stream()
                st.accept_waveform(SR, chunk)
                rec.decode_stream(st)
                texts.append(st.result.text.strip())
            t = " ".join(x for x in texts if x)
            totals[c] += len(words(t))
            lines.append(f"  {c:18s} {len(words(t)):4d} words | {t[:220]}")
        print("\n".join(lines[-len(CONFIGS) - 1:]), flush=True)
    lines.append("## TOTAL words: " + ", ".join(f"{c}: {n}" for c, n in totals.items()))
    print(lines[-1])
    Path(sys.argv[2]).write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
