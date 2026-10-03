import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
QWEN_DIR = Path(r"D:\nishant\toolchain\stt-eval\qwen3-hinglish")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def dynamic_agc(audio: np.ndarray, win_sec: float = 2.5, target_rms: float = 0.08) -> np.ndarray:
    win = int(win_sec * 16000)
    out = np.zeros_like(audio)
    for i in range(0, len(audio), win):
        chunk = audio[i:i+win]
        rms = np.sqrt(np.mean(chunk**2))
        if rms > 0.002: # above silence floor
            gain = min(6.0, target_rms / rms)
        else:
            gain = 1.0
        out[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)
    return out

print("--- Loading Models ---", flush=True)

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

rec_base_en = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

rec_qwen = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
    conv_frontend=str(QWEN_DIR / "conv_frontend.onnx"),
    encoder=str(QWEN_DIR / "encoder.int8.onnx"),
    decoder=str(QWEN_DIR / "decoder.int8.onnx"),
    tokenizer=str(QWEN_DIR / "tokenizer"),
    num_threads=4, max_new_tokens=256, max_total_len=1024,
)

print("--- Models Loaded ---\n", flush=True)

for name in ["40.wav", "39.wav"]:
    raw = read_wav(Path(f"tools/{name}"))
    dur = len(raw) / 16000.0
    boosted = dynamic_agc(raw)
    
    print(f"==================================================", flush=True)
    print(f"FILE: {name} (Duration: {dur:.2f}s)", flush=True)
    rms_raw = np.sqrt(np.mean(raw**2))
    rms_boosted = np.sqrt(np.mean(boosted**2))
    print(f"Raw RMS: {rms_raw:.4f} ({20*np.log10(rms_raw+1e-9):.1f} dB) -> Boosted RMS: {rms_boosted:.4f} ({20*np.log10(rms_boosted+1e-9):.1f} dB)", flush=True)
    
    # 1. Whole file test (if short <= 10s)
    if dur <= 10:
        print("\n--- Model Comparison on Full Audio ---", flush=True)
        # Base EN
        s = rec_base_en.create_stream()
        s.accept_waveform(16000, boosted)
        rec_base_en.decode_stream(s)
        print(f"Base (English):   {s.result.text.strip()}", flush=True)
        
        # Swift
        s = rec_swift.create_stream()
        s.accept_waveform(16000, boosted)
        rec_swift.decode_stream(s)
        print(f"Swift (Hinglish): {s.result.text.strip()}", flush=True)
        
        # Qwen
        s = rec_qwen.create_stream()
        s.accept_waveform(16000, boosted)
        rec_qwen.decode_stream(s)
        print(f"Qwen3-ASR:        {s.result.text.strip()}", flush=True)
    else:
        # Longer file: Compare by segments with VAD 0.35 on boosted audio
        print("\n--- Running Silero VAD (thresh=0.35) on Boosted Audio ---", flush=True)
        vcfg = sherpa_onnx.VadModelConfig()
        vcfg.silero_vad.model = str(VAD_MODEL)
        vcfg.silero_vad.threshold = 0.35
        vcfg.silero_vad.min_silence_duration = 0.3
        vcfg.silero_vad.min_speech_duration = 0.25
        vcfg.silero_vad.window_size = 512
        vcfg.sample_rate = 16000
        vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
        
        segments = []
        for i in range(0, len(boosted), 512):
            chunk = boosted[i:i+512]
            if len(chunk) < 512:
                chunk = np.pad(chunk, (0, 512 - len(chunk)))
            vad.accept_waveform(chunk)
            while not vad.empty():
                seg = vad.front
                vad.pop()
                seg_arr = np.array(seg.samples, dtype=np.float32)
                if len(seg_arr) > 4000:
                    start_s = seg.start / 16000.0
                    dur_s = len(seg_arr) / 16000.0
                    segments.append((start_s, start_s + dur_s, seg_arr))
        vad.flush()
        while not vad.empty():
            seg = vad.front
            vad.pop()
            seg_arr = np.array(seg.samples, dtype=np.float32)
            if len(seg_arr) > 4000:
                start_s = seg.start / 16000.0
                dur_s = len(seg_arr) / 16000.0
                segments.append((start_s, start_s + dur_s, seg_arr))
            
        print(f"Detected {len(segments)} speech segments with threshold 0.35.", flush=True)
        for idx, (t0, t1, seg_audio) in enumerate(segments):
            s_swift = rec_swift.create_stream()
            s_swift.accept_waveform(16000, seg_audio)
            rec_swift.decode_stream(s_swift)
            
            s_qwen = rec_qwen.create_stream()
            s_qwen.accept_waveform(16000, seg_audio)
            rec_qwen.decode_stream(s_qwen)
            
            print(f"\n[Segment {idx+1}: {t0:.1f}s - {t1:.1f}s ({t1-t0:.1f}s)]", flush=True)
            print(f"  Swift (Roman):  {s_swift.result.text.strip()}", flush=True)
            print(f"  Qwen3 (Hinglish): {s_qwen.result.text.strip()}", flush=True)
