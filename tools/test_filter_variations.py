import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)
rec_base = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
    decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
    tokens=str(BASE_DIR / "base-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

# 1. High-pass filter (IIR 1st order)
def high_pass(samples: np.ndarray, cutoff_hz: float = 80.0, fs: int = 16000) -> np.ndarray:
    rc = 1.0 / (2.0 * np.pi * cutoff_hz)
    dt = 1.0 / fs
    a = rc / (rc + dt)
    out = np.zeros_like(samples)
    prev_x = 0.0
    prev_y = 0.0
    for i in range(len(samples)):
        x = float(samples[i])
        y = a * (prev_y + x - prev_x)
        prev_x = x
        prev_y = y
        out[i] = y
    return out

# 2. Low-pass filter (IIR 1st order)
def low_pass(samples: np.ndarray, cutoff_hz: float = 7500.0, fs: int = 16000) -> np.ndarray:
    rc = 1.0 / (2.0 * np.pi * cutoff_hz)
    dt = 1.0 / fs
    a = dt / (rc + dt)
    out = np.zeros_like(samples)
    prev_y = 0.0
    for i in range(len(samples)):
        y = prev_y + a * (float(samples[i]) - prev_y)
        prev_y = y
        out[i] = y
    return out

# 3. Pre-emphasis filter
def pre_emphasis(samples: np.ndarray, alpha: float = 0.97) -> np.ndarray:
    out = np.zeros_like(samples)
    out[0] = samples[0]
    for i in range(1, len(samples)):
        out[i] = samples[i] - alpha * samples[i-1]
    return out

# 4. Soft gate as currently implemented in AudioPreprocessor
def soft_gate(samples: np.ndarray, noise_floor_db: float = -45.0, gate_db: float = -12.0, fs: int = 16000) -> np.ndarray:
    out = samples.copy()
    frame_len = fs // 40
    gate_gain = 10.0 ** (gate_db / 20.0)
    for i in range(0, len(out) - frame_len + 1, frame_len):
        chunk = out[i:i+frame_len]
        rms = np.sqrt(np.mean(chunk**2) + 1e-12)
        db = 20.0 * np.log10(rms)
        if db < noise_floor_db:
            out[i:i+frame_len] *= gate_gain
    return out

# 5. Peak/RMS normalizer
def normalize_gain(samples: np.ndarray, target_db: float = -20.0) -> np.ndarray:
    # 75th percentile of active frames
    frame_len = 400
    levels = []
    for i in range(0, len(samples) - frame_len + 1, frame_len):
        rms = np.sqrt(np.mean(samples[i:i+frame_len]**2) + 1e-12)
        db = 20.0 * np.log10(rms)
        if db > -55.0:
            levels.append(db)
    if not levels:
        levels = [-40.0]
    speech_db = np.percentile(levels, 75)
    gain_db = np.clip(target_db - speech_db, -6.0, 18.0)
    gain = 10.0 ** (gain_db / 20.0)
    return np.clip(samples * gain, -0.98, 0.98)

def run_vad_segments(samples: np.ndarray, thresh: float = 0.30):
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(VAD_MODEL)
    vcfg.silero_vad.threshold = thresh
    vcfg.silero_vad.min_silence_duration = 0.30
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
    
    for i in range(0, len(samples), 512):
        chunk = samples[i:i+512]
        if len(chunk) < 512:
            chunk = np.pad(chunk, (0, 512 - len(chunk)))
        vad.accept_waveform(chunk)
    vad.flush()
    segs = []
    while not vad.empty():
        s = vad.front
        arr = np.array(s.samples, dtype=np.float32)
        segs.append((s.start, arr))
        vad.pop()
    return segs

def transcribe(rec, audio: np.ndarray) -> str:
    s = rec.create_stream()
    s.accept_waveform(16000, audio)
    rec.decode_stream(s)
    return s.result.text.strip()

for wav_num in [42, 43]:
    p = Path(f"tools/{wav_num}.wav")
    if not p.exists(): continue
    raw = read_wav(p)
    
    print(f"\n=======================================================", flush=True)
    print(f"TESTING FILTER PIPELINES ON {wav_num}.wav", flush=True)
    
    # Filter Pipeline A: Current in app (HP 150Hz + LP 7kHz + Pre-emph 0.97 + Gain + SoftGate)
    hp_a = high_pass(raw, 150.0)
    lp_a = low_pass(hp_a, 7000.0)
    pe_a = pre_emphasis(lp_a, 0.97)
    norm_a = normalize_gain(pe_a)
    
    # Filter Pipeline B: No pre-emphasis (HP 100Hz + LP 7500Hz + Gain + SoftGate)
    hp_b = high_pass(raw, 100.0)
    lp_b = low_pass(hp_b, 7500.0)
    norm_b = normalize_gain(lp_b)
    
    # Filter Pipeline C: Pure Rumble cut only (HP 80Hz + Normalize, NO gate, NO pre-emph)
    # Why? Silero VAD already does speech detection; Whisper Mel filterbank does rest
    hp_c = high_pass(raw, 80.0)
    norm_c = normalize_gain(hp_c)
    
    # Filter Pipeline D: Raw + Gain only
    norm_d = normalize_gain(raw)

    pipelines = {
        "A (Current: HP150 + LP7k + PreEmph0.97 + Gate)": (norm_a, True),
        "B (HP100 + LP7.5k + Norm + Gate)": (norm_b, True),
        "C (Gentle HP80 + Norm, Clean - No distortion)": (norm_c, False),
        "D (Raw + Norm only)": (norm_d, False),
    }

    for name, (proc_audio, apply_gate) in pipelines.items():
        print(f"\n--- Pipeline: {name} ---", flush=True)
        segs = run_vad_segments(proc_audio, thresh=0.30)
        print(f"VAD Segments found: {len(segs)}")
        for idx, (st, arr) in enumerate(segs):
            t0 = st / 16000.0
            t1 = t0 + len(arr) / 16000.0
            
            # Subchunk to 10s
            for k in range(0, len(arr), 10 * 16000):
                sub = arr[k:k+10*16000]
                if len(sub) < 4000: continue
                sub_t0 = t0 + k / 16000.0
                sub_t1 = min(t1, sub_t0 + len(sub) / 16000.0)
                
                if apply_gate:
                    sub = soft_gate(sub)
                
                txt_sw = transcribe(rec_swift, sub)
                txt_base = transcribe(rec_base, sub)
                print(f"  [{sub_t0:5.2f}s - {sub_t1:5.2f}s] Swift:   \"{txt_sw}\"")
                print(f"  [{sub_t0:5.2f}s - {sub_t1:5.2f}s] Base:    \"{txt_base}\"")
