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

def dynamic_sliding_agc(audio: np.ndarray, win_sec: float = 2.0, target_rms: float = 0.08) -> np.ndarray:
    win = int(win_sec * 16000)
    out = np.zeros_like(audio)
    for i in range(0, len(audio), win):
        chunk = audio[i:i+win]
        rms = np.sqrt(np.mean(chunk**2))
        if rms > 0.002: # speech energy
            gain = min(5.0, target_rms / rms)
        else:
            gain = 1.0
        out[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)
    return out

def get_vad_segments(audio: np.ndarray, thresh: float = 0.30):
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = str(VAD_MODEL)
    vcfg.silero_vad.threshold = thresh
    vcfg.silero_vad.min_silence_duration = 0.35
    vcfg.silero_vad.min_speech_duration = 0.25
    vcfg.silero_vad.window_size = 512
    vcfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)
    
    for i in range(0, len(audio), 512):
        chunk = audio[i:i+512]
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

# Name resolver
def resolve_names(text: str, user_name: str = "Nishant Jain") -> str:
    import re
    # If text has Nisanjian / Nissan Jain etc, map to user_name
    pattern = re.compile(r"\b(nisanjian|nishaanjan|neesan jain|nissan jain|nishaan jain)\b", re.IGNORECASE)
    return pattern.sub(user_name, text)

for fname in ["40.wav", "39.wav"]:
    p = Path(f"tools/{fname}")
    if not p.exists(): continue
    with wave.open(str(p), "rb") as w:
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    
    dur = len(raw) / 16000.0
    print(f"\n=======================================================", flush=True)
    print(f"PROCESSING {fname} (Duration: {dur:.2f}s) WITH PROPOSED FIXES", flush=True)
    
    boosted = dynamic_sliding_agc(raw)
    segs = get_vad_segments(boosted, thresh=0.30)
    print(f"Total VAD Segments: {len(segs)}")
    
    for idx, (st, arr) in enumerate(segs):
        t0 = st / 16000.0
        t1 = t0 + len(arr) / 16000.0
        
        # Max chunk 25s like Android app
        max_chunk = 25 * 16000
        chunks = [arr[k:k+max_chunk] for k in range(0, len(arr), max_chunk)]
        
        for c_idx, chunk in enumerate(chunks):
            sub_t0 = t0 + c_idx * 25.0
            sub_t1 = min(t1, sub_t0 + len(chunk)/16000.0)
            
            # Base EN
            s_base = rec_base_en.create_stream()
            s_base.accept_waveform(16000, chunk)
            rec_base_en.decode_stream(s_base)
            txt_base = resolve_names(s_base.result.text.strip())
            
            # Swift
            s_swift = rec_swift.create_stream()
            s_swift.accept_waveform(16000, chunk)
            rec_swift.decode_stream(s_swift)
            txt_swift = resolve_names(s_swift.result.text.strip())
            
            print(f"  [{sub_t0:.2f}s - {sub_t1:.2f}s]:")
            print(f"     Swift:   \"{txt_swift}\"")
            print(f"     Base EN: \"{txt_base}\"")
