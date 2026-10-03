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

def current_app_preprocessor(audio: np.ndarray) -> np.ndarray:
    # Current AudioPreprocessor in app: 90th percentile level on whole file, static gain
    # Window of 100ms
    win = 1600
    levels = []
    for i in range(0, len(audio) - win, win):
        chunk = audio[i:i+win]
        rms = np.sqrt(np.mean(chunk**2))
        levels.append(rms)
    if not levels:
        return audio
    p90 = np.percentile(levels, 90)
    # App code: gain = (target / p90).coerceIn(1.0, 3.5) where target is 0.08
    gain = min(3.5, max(1.0, 0.08 / (p90 + 1e-6)))
    return np.clip(audio * gain, -0.98, 0.98)

def dynamic_sliding_agc(audio: np.ndarray, win_sec: float = 2.0, target_rms: float = 0.08) -> np.ndarray:
    # Continuous sliding window AGC with smooth gain transitions
    win = int(win_sec * 16000)
    out = np.zeros_like(audio)
    for i in range(0, len(audio), win):
        chunk = audio[i:i+win]
        rms = np.sqrt(np.mean(chunk**2))
        if rms > 0.003: # Speech energy detected
            gain = min(5.0, max(1.0, target_rms / rms))
        else:
            gain = 1.0
        out[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)
    return out

print("--- Loading Swift & Whisper Base (Fast CPU) ---", flush=True)

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

rec_base_hi = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="hi", task="transcribe", num_threads=4,
)

def run_vad(audio: np.ndarray, threshold: float = 0.35, min_silence: float = 0.3):
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(VAD_MODEL)
    vcfg.silero_vad.threshold = threshold
    vcfg.silero_vad.min_silence_duration = min_silence
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
    
    segments = []
    for i in range(0, len(audio), 512):
        chunk = audio[i:i+512]
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
    return segments

def transcribe_chunk(rec, chunk: np.ndarray) -> str:
    s = rec.create_stream()
    s.accept_waveform(16000, chunk)
    rec.decode_stream(s)
    return s.result.text.strip()

for fname in ["38.wav", "40.wav", "39.wav"]:
    fpath = Path(f"tools/{fname}")
    if not fpath.exists(): continue
    raw = read_wav(fpath)
    dur = len(raw) / 16000.0
    print(f"\n=======================================================", flush=True)
    print(f"FILE: {fname} (Length: {dur:.2f}s, Max Peak: {np.max(np.abs(raw)):.4f}, Overall RMS: {np.sqrt(np.mean(raw**2)):.4f})", flush=True)
    
    # Compare raw vs app preprocessor vs dynamic AGC
    old_proc = current_app_preprocessor(raw)
    dyn_proc = dynamic_sliding_agc(raw)
    print(f"Old Preprocessor RMS: {np.sqrt(np.mean(old_proc**2)):.4f}, Peak: {np.max(np.abs(old_proc)):.4f}")
    print(f"Dynamic AGC RMS:      {np.sqrt(np.mean(dyn_proc**2)):.4f}, Peak: {np.max(np.abs(dyn_proc)):.4f}")
    
    # Check VAD detection on Old Preprocessor with threshold 0.50 (current app)
    old_vad = run_vad(old_proc, threshold=0.50)
    print(f"\n[CURRENT APP SETTINGS: thresh=0.50, static preprocessor]")
    print(f"Speech detected: {len(old_vad)} segments (Total speech time: {sum(t1-t0 for t0,t1,_ in old_vad):.1f}s / {dur:.1f}s)")
    for t0, t1, chunk in old_vad:
        txt = transcribe_chunk(rec_base_en, chunk)
        print(f"  [{t0:.1f}s - {t1:.1f}s]: Base EN='{txt}'")
        
    # Check VAD detection on Dynamic AGC with threshold 0.30 (proposed)
    new_vad = run_vad(dyn_proc, threshold=0.30)
    print(f"\n[PROPOSED SETTINGS: thresh=0.30, dynamic sliding AGC]")
    print(f"Speech detected: {len(new_vad)} segments (Total speech time: {sum(t1-t0 for t0,t1,_ in new_vad):.1f}s / {dur:.1f}s)")
    for t0, t1, chunk in new_vad:
        txt_en = transcribe_chunk(rec_base_en, chunk)
        txt_hi = transcribe_chunk(rec_base_hi, chunk)
        txt_sw = transcribe_chunk(rec_swift, chunk)
        print(f"  [{t0:.1f}s - {t1:.1f}s]:")
        print(f"     Swift:   {txt_sw}")
        print(f"     Base EN: {txt_en}")
        print(f"     Base HI: {txt_hi}")
