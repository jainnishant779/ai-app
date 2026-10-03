import os
import sys
import subprocess
import sqlite3
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

adb = r"C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZA222ZJMQG"

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
VAD_MODEL = Path(r"D:\nishant\llm_research\llama_bin\stt\silero_vad.onnx")

print("Loading recognizers...")
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

# Pull DB again to get latest status
db_path = Path("tools/nishu_device.db")
with open(db_path, "wb") as f:
    subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", "databases/nishu.db"], stdout=f, check=True)

con = sqlite3.connect(db_path)
cur = con.cursor()

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def transcribe_audio(rec, samples: np.ndarray) -> str:
    stream = rec.create_stream()
    stream.accept_waveform(16000, samples)
    rec.decode_stream(stream)
    return stream.result.text.strip()

# Target audio IDs: 68 (second to last) and 69 (latest processed)
target_ids = [68, 69]

for cid in target_ids:
    print(f"\n{'='*70}")
    # 1. Pull audio
    wav_path = Path(f"tools/{cid}.wav")
    with open(wav_path, "wb") as f:
        subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", f"files/recordings/{cid}.wav"], stdout=f, check=True)
    
    # 2. Get DB info
    conv = cur.execute("SELECT id, title, status, durationMs FROM conversation WHERE id = ?", (cid,)).fetchone()
    stored_lines = cur.execute("SELECT startMs, endMs, text, speakerLabel FROM transcript_segment WHERE conversationId = ? ORDER BY startMs", (cid,)).fetchall()
    
    print(f"AUDIO ID: {cid} | Title: {conv[1]} | Status in App: {conv[2]} | Duration: {conv[3]/1000.0:.1f}s")
    print(f"File size on disk: {wav_path.stat().st_size} bytes")
    
    print("\n--- [1] STORED TRANSCRIPT IN APP (from mobile SQLite) ---")
    if not stored_lines:
        print("  (No transcript lines stored in DB)")
    else:
        for tl in stored_lines:
            print(f"  [{tl[0]/1000.0:5.1f}s - {tl[1]/1000.0:5.1f}s] ({tl[3]}): {tl[2]}")
    
    # 3. Direct PC Transcription (Full Audio)
    samples = read_wav(wav_path)
    dur = len(samples) / 16000.0
    rms = np.sqrt(np.mean(samples**2))
    print(f"\nAudio stats: Length={dur:.2f}s, RMS={rms:.4f}")
    
    # Direct Hinglish-Swift
    swift_text = transcribe_audio(rec_swift, samples)
    print(f"\n--- [2] DIRECT PC TRANSCRIPTION: hinglish-swift (Exact Model) ---")
    print(f"  {swift_text if swift_text else '(empty)'}")
    
    # Direct Whisper Base English
    base_en_text = transcribe_audio(rec_base_en, samples)
    print(f"\n--- [3] DIRECT PC TRANSCRIPTION: whisper-base (English) ---")
    print(f"  {base_en_text if base_en_text else '(empty)'}")
    
    # Direct Whisper Base Hindi
    base_hi_text = transcribe_audio(rec_base_hi, samples)
    print(f"\n--- [4] DIRECT PC TRANSCRIPTION: whisper-base (Hindi) ---")
    print(f"  {base_hi_text if base_hi_text else '(empty)'}")

con.close()
