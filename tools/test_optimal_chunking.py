import wave, numpy as np, sherpa_onnx
from pathlib import Path

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

rec_swift = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
    decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
    tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
    language="en", task="transcribe", num_threads=4,
)

with wave.open("tools/39.wav", "rb") as w:
    raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

# Dynamic AGC
win = int(2.0 * 16000)
boosted = np.zeros_like(raw)
for i in range(0, len(raw), win):
    chunk = raw[i:i+win]
    rms = np.sqrt(np.mean(chunk**2))
    gain = min(5.0, 0.08 / (rms + 1e-6))
    boosted[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)

# VAD with min_silence = 0.30s (natural breath pause)
vcfg = sherpa_onnx.VadModelConfig()
vcfg.silero_vad.model = str(VAD_MODEL)
vcfg.silero_vad.threshold = 0.30
vcfg.silero_vad.min_silence_duration = 0.30
vcfg.silero_vad.min_speech_duration = 0.25
vcfg.silero_vad.window_size = 512
vcfg.sample_rate = 16000
vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)

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

print(f"Total VAD speech segments: {len(segs)}")
full_transcript = []

# Max chunk 10s so Whisper never terminates early on internal pause
MAX_SEC = 10.0
max_samples = int(MAX_SEC * 16000)

for idx, (st, arr) in enumerate(segs):
    t0 = st / 16000.0
    
    # Split if longer than MAX_SEC
    for k in range(0, len(arr), max_samples):
        sub_arr = arr[k:k+max_samples]
        if len(sub_arr) < 4000: continue # under 0.25s
        
        sub_t0 = t0 + k / 16000.0
        sub_t1 = sub_t0 + len(sub_arr) / 16000.0
        
        s = rec_swift.create_stream()
        s.accept_waveform(16000, sub_arr)
        rec_swift.decode_stream(s)
        txt = s.result.text.strip()
        if txt and txt.lower() not in ["[music]", "me"]:
            full_transcript.append((sub_t0, sub_t1, txt))
            print(f"[{sub_t0:5.1f}s - {sub_t1:5.1f}s]: {txt}")

print("\n--- COMPLETE RECONSTRUCTED TRANSCRIPT ---")
for t0, t1, txt in full_transcript:
    print(f"{txt}", end=" ")
print("\n")
