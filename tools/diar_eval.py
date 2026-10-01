"""Speaker diarization sanity check on the desktop: pyannote segmentation + embedding model + clustering."""
import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

D = Path(r"D:\nishant\toolchain\diar")
SEG = D / "sherpa-onnx-pyannote-segmentation-3-0" / "model.int8.onnx"
EMB = {
    "wespeaker CAM++ (en)": D / "wespeaker_camplusplus.onnx",
    "3dspeaker CAM++ (zh+en)": D / "3dspeaker_zh_en_adv.onnx",
}


def read(path):
    with wave.open(str(path)) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    return x, sr


def diarize(emb, samples, threshold=0.5, num_speakers=-1):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(SEG)), num_threads=2),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(emb), num_threads=2),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_speakers, threshold=threshold),
        min_duration_on=0.3, min_duration_off=0.5,
    )
    assert cfg.validate(), "bad config"
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    t = time.time()
    res = sd.process(samples).sort_by_start_time()
    return res, time.time() - t


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    files = [Path(p) for p in sys.argv[1:]] or [D / "two1.wav", D / "two3.wav"]
    for f in files:
        x, sr = read(f)
        if sr != 16000:
            print(f"{f.name}: skipped (sample rate {sr})")
            continue
        print(f"=== {f.name}  {len(x)/16000:.1f}s")
        for name, emb in EMB.items():
            for thr in (0.5,):
                res, dt = diarize(emb, x, thr)
                speakers = sorted({r.speaker for r in res})
                print(f"-- {name} thr={thr}: {len(speakers)} speakers, {len(res)} turns, {dt:.1f}s ({dt/(len(x)/16000):.2f}x realtime)")
                for r in res[:14]:
                    print(f"   {r.start:6.2f} - {r.end:6.2f}  speaker_{r.speaker}")


if __name__ == "__main__":
    main()
