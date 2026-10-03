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

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

for num in [42, 43]:
    p = Path(f"tools/{num}.wav")
    raw = read_wav(p)
    dur = len(raw) / 16000.0
    rms = np.sqrt(np.mean(raw**2))
    peak = np.max(np.abs(raw))
    db = 20 * np.log10(rms + 1e-9)
    print(f"\n=======================================================", flush=True)
    print(f"ANALYSIS OF {num}.wav (Duration: {dur:.2f}s, Peak: {peak:.4f}, RMS: {rms:.4f} / {db:.1f} dB)", flush=True)
    
    # Run Silero VAD
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(VAD_MODEL)
    vcfg.silero_vad.threshold = 0.30
    vcfg.silero_vad.min_silence_duration = 0.30
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
    
    # Dynamic gain
    win = int(2.0 * 16000)
    boosted = np.zeros_like(raw)
    for i in range(0, len(raw), win):
        chunk = raw[i:i+win]
        c_rms = np.sqrt(np.mean(chunk**2))
        gain = min(5.0, 0.08 / (c_rms + 1e-6))
        boosted[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)
        
    for i in range(0, len(boosted), 512):
        chunk = boosted[i:i+512]
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
        
    print(f"Total VAD Segments: {len(segs)}", flush=True)
    
    # Slices max 10s
    for idx, (st, arr) in enumerate(segs):
        t0 = st / 16000.0
        max_samples = 10 * 16000
        for k in range(0, len(arr), max_samples):
            sub_arr = arr[k:k+max_samples]
            if len(sub_arr) < 4000: continue
            sub_t0 = t0 + k / 16000.0
            sub_t1 = sub_t0 + len(sub_arr) / 16000.0
            
            # Swift
            s_sw = rec_swift.create_stream()
            s_sw.accept_waveform(16000, sub_arr)
            rec_swift.decode_stream(s_sw)
            txt_sw = s_sw.result.text.strip()
            
            # Base EN
            s_en = rec_base_en.create_stream()
            s_en.accept_waveform(16000, sub_arr)
            rec_base_en.decode_stream(s_en)
            txt_en = s_en.result.text.strip()
            
            # Base HI
            s_hi = rec_base_hi.create_stream()
            s_hi.accept_waveform(16000, sub_arr)
            rec_base_hi.decode_stream(s_hi)
            txt_hi = s_hi.result.text.strip()
            
            print(f"  [{sub_t0:5.2f}s - {sub_t1:5.2f}s]:", flush=True)
            print(f"     Swift:   \"{txt_sw}\"", flush=True)
            print(f"     Base EN: \"{txt_en}\"", flush=True)
            print(f"     Base HI: \"{txt_hi}\"", flush=True)
