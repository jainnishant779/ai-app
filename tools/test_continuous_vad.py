import sys
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
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
win = int(2.5 * 16000)
boosted = np.zeros_like(raw)
for i in range(0, len(raw), win):
    chunk = raw[i:i+win]
    rms = np.sqrt(np.mean(chunk**2))
    gain = min(6.0, 0.08 / max(0.002, rms))
    boosted[i:i+win] = np.clip(chunk * gain, -0.98, 0.98)

vcfg = sherpa_onnx.VadModelConfig()
vcfg.silero_vad.model = str(VAD_MODEL)
vcfg.silero_vad.threshold = 0.30
vcfg.silero_vad.min_silence_duration = 0.4
vcfg.silero_vad.min_speech_duration = 0.25
vcfg.silero_vad.max_speech_duration = 20.0
vcfg.sample_rate = 16000
vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=120)

for i in range(0, len(boosted), 512):
    chunk = boosted[i:i+512]
    if len(chunk) < 512:
        chunk = np.pad(chunk, (0, 512 - len(chunk)))
    vad.accept_waveform(chunk)

vad.flush()

print("--- Decoding Natural VAD Segments (Thresh=0.30, MaxSpeech=20s) ---", flush=True)
seg_idx = 0
while not vad.empty():
    seg = vad.front
    vad.pop()
    samples = np.array(seg.samples, dtype=np.float32)
    if len(samples) < 4000:
        continue
    seg_idx += 1
    t0 = seg.start / 16000.0
    dur = len(samples) / 16000.0
    st = rec_swift.create_stream()
    st.accept_waveform(16000, samples)
    rec_swift.decode_stream(st)
    print(f"[{seg_idx}] {t0:.1f}s - {t0+dur:.1f}s ({dur:.1f}s): {st.result.text.strip()}", flush=True)
