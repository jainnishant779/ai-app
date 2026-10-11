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

def vad_segment(samples: np.ndarray, vad_path: Path):
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(vad_path)
    vcfg.silero_vad.threshold = 0.30
    vcfg.silero_vad.min_silence_duration = 0.45
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=60)
    
    segments = []
    for i in range(0, len(samples), 512):
        chunk = samples[i:i+512]
        if len(chunk) < 512:
            chunk = np.pad(chunk, (0, 512 - len(chunk)))
        vad.accept_waveform(chunk)
        while not vad.empty():
            seg = vad.front
            vad.pop()
            arr = np.array(seg.samples, dtype=np.float32)
            if len(arr) > 4000:
                start_s = seg.start / 16000.0
                dur_s = len(arr) / 16000.0
                segments.append((start_s, start_s + dur_s, arr))
    vad.flush()
    while not vad.empty():
        seg = vad.front
        vad.pop()
        arr = np.array(seg.samples, dtype=np.float32)
        if len(arr) > 4000:
            start_s = seg.start / 16000.0
            dur_s = len(arr) / 16000.0
            segments.append((start_s, start_s + dur_s, arr))
    return segments

def main():
    qwen_dir = Path("tools/models/qwen3-hinglish")
    vad_path = Path("tools/models/silero_vad.onnx")
    
    print("Loading Qwen3-ASR model...", flush=True)
    rec_qwen = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=str(qwen_dir / "conv_frontend.onnx"),
        encoder=str(qwen_dir / "encoder.int8.onnx"),
        decoder=str(qwen_dir / "decoder.int8.onnx"),
        tokenizer=str(qwen_dir / "tokenizer"),
        num_threads=4,
        max_total_len=1024,
        max_new_tokens=448,
    )
    print("Qwen3-ASR loaded successfully!\n", flush=True)

    for test_id in [198, 199]:
        wav_path = Path(f"tools/{test_id}.wav")
        if not wav_path.exists():
            continue
        audio = read_wav(wav_path)
        print(f"============================================================", flush=True)
        print(f"RECORDING: {test_id}.wav (Duration: {len(audio)/16000:.2f}s)", flush=True)
        print(f"============================================================", flush=True)
        
        # 1. Whole audio transcription
        s_full = rec_qwen.create_stream()
        s_full.accept_waveform(16000, audio)
        rec_qwen.decode_stream(s_full)
        print(f"[Qwen3-ASR Full Audio Transcription]:", flush=True)
        print(f"  \"{s_full.result.text.strip()}\"\n", flush=True)

        # 2. VAD segment transcription (App-style)
        segs = vad_segment(audio, vad_path)
        print(f"[Qwen3-ASR VAD Segments ({len(segs)} segments)]:", flush=True)
        for idx, (t0, t1, seg_audio) in enumerate(segs):
            st = rec_qwen.create_stream()
            st.accept_waveform(16000, seg_audio)
            rec_qwen.decode_stream(st)
            print(f"  Segment {idx+1} ({t0:.2f}s - {t1:.2f}s): \"{st.result.text.strip()}\"", flush=True)
        print("\n", flush=True)

if __name__ == "__main__":
    main()
