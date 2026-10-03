r"""Run an excerpt through Qwen3-ASR (sherpa-onnx) with the same chunking as the app, to compare against the whisper models.

Usage: .\.venv\Scripts\python.exe tools\stt_qwen3.py <model-dir> <wav> <start_s> <duration_s> <out.txt>
<model-dir> holds encoder.int8.onnx, decoder.int8.onnx, conv_frontend.onnx and tokenizer/ (vocab.json, merges.txt, tokenizer_config.json).
"""
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).parent))
from stt_ablation import batch, vad_segments  # noqa: E402
from stt_eval import preprocess, read_wav  # noqa: E402


def main():
    d, wav, start, dur, out = Path(sys.argv[1]), Path(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), Path(sys.argv[5])
    x = read_wav(wav)[int(start * 16000):int((start + dur) * 16000)]
    segs = batch(vad_segments(preprocess(x)), max_s=25)
    rec = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=str(d / "conv_frontend.onnx"), encoder=str(d / "encoder.int8.onnx"),
        decoder=str(d / "decoder.int8.onnx"), tokenizer=str(d / "tokenizer"),
        num_threads=4, max_new_tokens=256, max_total_len=1024,
    )
    t0 = time.time()
    texts = []
    for s in segs:
        st = rec.create_stream()
        st.accept_waveform(16000, s)
        rec.decode_stream(st)
        texts.append(st.result.text.strip())
    secs = time.time() - t0
    speech = sum(len(s) for s in segs) / 16000
    text = f"# {wav.name} {start:.0f}-{start + dur:.0f}s: {len(segs)} chunks, {speech:.0f}s speech, decode {secs:.0f}s (RTF {secs / (len(x) / 16000):.2f})\n" + " ".join(texts)
    out.write_text(text, encoding="utf-8")
    print(text[:3000])


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
