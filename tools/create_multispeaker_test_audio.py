"""
Creates a 3.5-minute (210s) multi-speaker conversation audio with a 5-second overlapping section
using real Motorola recorded audio files.
"""
import wave
import numpy as np

FS = 16000

def load_pcm(path):
    with wave.open(path, 'rb') as w:
        frames = w.getnframes()
        data = w.readframes(frames)
        samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
        return samples

def save_pcm(path, samples):
    int16_data = (np.clip(samples, -0.999, 0.999) * 32767.0).astype(np.int16)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(FS)
        w.writeframes(int16_data.tobytes())

def create_silence(duration_sec):
    return np.zeros(int(duration_sec * FS), dtype=np.float32)

def build_meeting():
    spk2_intro = load_pcm("tools/198.wav")      # ~27.2s (Speaker 2)
    spk1_body = load_pcm("tools/22.wav")        # ~62.9s (Speaker 1)
    spk2_response = load_pcm("tools/199.wav")    # ~26.9s (Speaker 2)
    spk1_wrapup = load_pcm("tools/23.wav")      # ~36.5s (Speaker 1)
    spk1_extra = load_pcm("tools/24.wav")       # ~13.7s (Speaker 1)
    spk2_dialog = load_pcm("tools/7.wav")       # ~27.9s (Speaker 2 / Hindi)
    spk1_closing = load_pcm("tools/11.wav")     # ~11.7s (Speaker 1)

    # Overlap section: 5.0 seconds mixing spk1_extra[:5s] + spk2_intro[:5s]
    overlap_len = 5 * FS
    overlap = (spk1_extra[:overlap_len] * 0.7) + (spk2_intro[:overlap_len] * 0.7)

    parts = [
        spk2_intro,             # 0:00 - ~0:27
        create_silence(2.0),
        spk1_body,              # ~0:29 - ~1:32
        create_silence(2.0),
        spk2_response,          # ~1:34 - ~2:01
        create_silence(1.0),
        overlap,                # ~2:02 - ~2:07 (OVERLAP / CROSS-TALK)
        create_silence(1.0),
        spk1_wrapup,            # ~2:08 - ~2:45
        create_silence(2.0),
        spk2_dialog,            # ~2:47 - ~3:15
        create_silence(1.5),
        spk1_closing            # ~3:16 - ~3:28
    ]

    combined = np.concatenate(parts)
    duration = len(combined) / FS
    print(f"Combined meeting audio created: {duration:.2f} seconds ({len(combined)} samples)")
    save_pcm("tools/test_meeting_3m30s.wav", combined)
    return duration

if __name__ == "__main__":
    build_meeting()
