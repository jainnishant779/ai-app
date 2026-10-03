import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)
rec_base_hi = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="hi", task="transcribe", num_threads=4,
)
rec_base_en = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

for name in ["38.wav", "39.wav"]:
    print(f"\n=======================================================", flush=True)
    print(f"Analyzing {name}...", flush=True)
    samples = read_wav(Path(f"tools/{name}"))
    dur = len(samples) / 16000.0
    print(f"Total duration: {dur:.2f}s, samples: {len(samples)}", flush=True)
    
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(VAD_MODEL)
    config.silero_vad.threshold = 0.4
    config.silero_vad.min_silence_duration = 0.25
    config.silero_vad.min_speech_duration = 0.25
    config.silero_vad.window_size = 512
    config.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    
    window_size = 512
    segments = []
    for i in range(0, len(samples), window_size):
        chunk = samples[i:i+window_size]
        if len(chunk) < window_size:
            chunk = np.pad(chunk, (0, window_size - len(chunk)))
        vad.accept_waveform(chunk)
        while not vad.empty():
            seg = vad.front
            vad.pop()
            seg_arr = np.array(seg.samples, dtype=np.float32)
            if len(seg_arr) > 4000: # at least 0.25s
                start_s = seg.start / 16000.0
                seg_dur = len(seg_arr) / 16000.0
                segments.append((start_s, start_s + seg_dur, seg_arr))
    
    vad.flush()
    while not vad.empty():
        seg = vad.front
        vad.pop()
        seg_arr = np.array(seg.samples, dtype=np.float32)
        if len(seg_arr) > 4000:
            start_s = seg.start / 16000.0
            seg_dur = len(seg_arr) / 16000.0
            segments.append((start_s, start_s + seg_dur, seg_arr))
        
    print(f"Found {len(segments)} speech segments.", flush=True)
    
    for s_start, s_end, seg_samples in segments:
        st1 = rec_swift.create_stream()
        st1.accept_waveform(16000, seg_samples)
        rec_swift.decode_stream(st1)
        txt_swift = st1.result.text.strip()
        
        st2 = rec_base_hi.create_stream()
        st2.accept_waveform(16000, seg_samples)
        rec_base_hi.decode_stream(st2)
        txt_base_hi = st2.result.text.strip()
        
        st3 = rec_base_en.create_stream()
        st3.accept_waveform(16000, seg_samples)
        rec_base_en.decode_stream(st3)
        txt_base_en = st3.result.text.strip()
        
        print(f"\n[{s_start:.1f}s - {s_end:.1f}s (dur {s_end-s_start:.1f}s)]:", flush=True)
        print(f"  Swift (Roman Hinglish):  {txt_swift}", flush=True)
        print(f"  Base (Hindi Devanagari): {txt_base_hi}", flush=True)
        print(f"  Base (English Mode):     {txt_base_en}", flush=True)
