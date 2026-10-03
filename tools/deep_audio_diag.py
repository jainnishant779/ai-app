"""Deep audio diagnostics: spectral profile, SNR, noise floor, frequency analysis."""
import sys, wave
import numpy as np
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

def read_wav(p):
    with wave.open(str(p), "rb") as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

for num in [42, 43]:
    p = Path(f"tools/{num}.wav")
    raw = read_wav(p)
    sr = 16000
    dur = len(raw) / sr
    
    print(f"\n{'='*70}")
    print(f"DEEP AUDIO DIAGNOSTICS: {num}.wav ({dur:.2f}s)")
    print(f"{'='*70}")
    
    # 1. Overall stats
    rms = np.sqrt(np.mean(raw**2))
    peak = np.max(np.abs(raw))
    db = 20 * np.log10(rms + 1e-9)
    print(f"\n--- Signal Stats ---")
    print(f"  RMS: {rms:.5f} ({db:.1f} dB)")
    print(f"  Peak: {peak:.5f} ({20*np.log10(peak+1e-9):.1f} dB)")
    print(f"  Crest Factor: {peak/rms:.1f}")
    print(f"  DC Offset: {np.mean(raw):.6f}")
    
    # 2. Noise floor analysis (look at quietest 10% of frames)
    frame_len = 400  # 25ms
    frame_rms = []
    for i in range(0, len(raw) - frame_len, frame_len):
        fr = raw[i:i+frame_len]
        fr_rms = np.sqrt(np.mean(fr**2))
        frame_rms.append(fr_rms)
    frame_rms = np.array(frame_rms)
    frame_db = 20 * np.log10(frame_rms + 1e-9)
    
    sorted_db = np.sort(frame_db)
    noise_floor = np.median(sorted_db[:len(sorted_db)//10])
    speech_level = np.median(sorted_db[len(sorted_db)*7//10:])
    snr = speech_level - noise_floor
    
    print(f"\n--- Noise Floor ---")
    print(f"  Noise floor (quietest 10%): {noise_floor:.1f} dB")
    print(f"  Speech level (top 30%): {speech_level:.1f} dB")
    print(f"  Estimated SNR: {snr:.1f} dB")
    print(f"  Frame dB range: [{sorted_db[0]:.1f}, {sorted_db[-1]:.1f}]")
    print(f"  Frames below -50 dB: {np.sum(frame_db < -50)}/{len(frame_db)} ({100*np.sum(frame_db<-50)/len(frame_db):.0f}%)")
    print(f"  Frames below -40 dB: {np.sum(frame_db < -40)}/{len(frame_db)} ({100*np.sum(frame_db<-40)/len(frame_db):.0f}%)")
    
    # 3. Frequency analysis (FFT of full signal)
    n_fft = 1024
    hop = 512
    freqs = np.fft.rfftfreq(n_fft, 1/sr)
    
    # Average magnitude spectrum
    specs = []
    for i in range(0, len(raw) - n_fft, hop):
        chunk = raw[i:i+n_fft] * np.hanning(n_fft)
        mag = np.abs(np.fft.rfft(chunk))
        specs.append(mag)
    avg_spec = np.mean(specs, axis=0)
    avg_spec_db = 20 * np.log10(avg_spec + 1e-9)
    
    # Find energy in frequency bands
    bands = [
        ("Sub-bass (0-80 Hz)", 0, 80),
        ("Bass/Rumble (80-200 Hz)", 80, 200),
        ("Low-mid (200-500 Hz)", 200, 500),
        ("Speech fundamental (500-2000 Hz)", 500, 2000),
        ("Speech clarity (2000-4000 Hz)", 2000, 4000),
        ("High freq/noise (4000-8000 Hz)", 4000, 8000),
    ]
    
    print(f"\n--- Frequency Band Energy ---")
    total_energy = np.sum(avg_spec**2)
    for name, lo, hi in bands:
        mask = (freqs >= lo) & (freqs < hi)
        band_energy = np.sum(avg_spec[mask]**2)
        pct = 100 * band_energy / total_energy
        band_db = 10 * np.log10(band_energy + 1e-9)
        print(f"  {name}: {pct:.1f}% ({band_db:.1f} dB)")
    
    # 4. Per-second analysis
    print(f"\n--- Per-Second Energy Profile ---")
    for sec in range(int(dur)):
        chunk = raw[sec*sr:(sec+1)*sr]
        c_rms = np.sqrt(np.mean(chunk**2))
        c_db = 20 * np.log10(c_rms + 1e-9)
        c_peak = np.max(np.abs(chunk))
        bar = "#" * max(0, int((c_db + 60) / 1.5))
        print(f"  {sec:3d}s: {c_db:6.1f} dB  peak={c_peak:.3f}  {bar}")
    
    # 5. Zero crossing rate (indicator of noise vs speech)
    zcr_frames = []
    for i in range(0, len(raw) - frame_len, frame_len):
        fr = raw[i:i+frame_len]
        zc = np.sum(np.abs(np.diff(np.sign(fr))) > 0) / (2 * frame_len)
        zcr_frames.append(zc)
    zcr = np.array(zcr_frames)
    print(f"\n--- Zero Crossing Rate ---")
    print(f"  Mean ZCR: {np.mean(zcr):.4f}")
    print(f"  Speech-like frames (ZCR < 0.15): {np.sum(zcr < 0.15)}/{len(zcr)} ({100*np.sum(zcr<0.15)/len(zcr):.0f}%)")
    print(f"  Noise-like frames (ZCR > 0.30): {np.sum(zcr > 0.30)}/{len(zcr)} ({100*np.sum(zcr>0.30)/len(zcr):.0f}%)")

print("\n\nDONE.")
