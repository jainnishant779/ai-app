"""
Ablation Benchmark: Raw Audio vs Old DSP vs New DSP
Transcribed with on-device Swift Hinglish model (sherpa-onnx INT8).
"""
import wave
import numpy as np
import sherpa_onnx
import time

FS = 16000

def read_wav(path):
    with wave.open(path, 'rb') as w:
        frames = w.getnframes()
        data = w.readframes(frames)
        samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
        return samples

# ==================== DSP IMPLEMENTATIONS ====================

def old_dsp(samples):
    """Old 1-pole HPF (80 Hz) + static RMS scaling + hard clamp [-0.98, 0.98]"""
    # 1. Old 1-pole HPF
    rc = 1.0 / (2.0 * np.pi * 80.0)
    dt = 1.0 / FS
    alpha = rc / (rc + dt)
    out = np.zeros_like(samples)
    y_prev = 0.0
    x_prev = 0.0
    for i in range(len(samples)):
        y = alpha * (y_prev + samples[i] - x_prev)
        out[i] = y
        y_prev = y
        x_prev = samples[i]
    
    # 2. Static RMS scaling
    rms = np.sqrt(np.mean(out**2) + 1e-12)
    target_rms = 0.10
    if rms > 1e-4:
        gain = min(target_rms / rms, 4.0)
        out = out * gain
    
    # 3. Hard clamp
    out = np.clip(out, -0.98, 0.98)
    return out

def new_dsp(samples):
    """New Pipeline: DC blocker + 2nd-order Butterworth HPF (80 Hz) + Notch (if hum) + BS.1770 gated + tanh limiter"""
    # 1. DC Blocker (fc ~ 20 Hz, alpha ~ 0.992)
    alpha_dc = 0.992
    dc_out = np.zeros_like(samples)
    y_prev = 0.0
    x_prev = 0.0
    for i in range(len(samples)):
        y = samples[i] - x_prev + alpha_dc * y_prev
        dc_out[i] = y
        y_prev = y
        x_prev = samples[i]
    
    # 2. 2nd-order Butterworth HPF (80 Hz, Q=0.7071)
    fc_hp = 80.0
    q_hp = 0.70710678
    w0 = 2.0 * np.pi * fc_hp / FS
    cos_w0 = np.cos(w0)
    sin_w0 = np.sin(w0)
    alpha = sin_w0 / (2.0 * q_hp)
    a0 = 1.0 + alpha
    b = np.array([(1.0 + cos_w0)/(2.0 * a0), -(1.0 + cos_w0)/a0, (1.0 + cos_w0)/(2.0 * a0)])
    a = np.array([1.0, (-2.0 * cos_w0)/a0, (1.0 - alpha)/a0])
    
    # Direct Form II Transposed
    hpf_out = np.zeros_like(dc_out)
    s1, s2 = 0.0, 0.0
    for i in range(len(dc_out)):
        x = dc_out[i]
        y = b[0] * x + s1
        s1 = b[1] * x - a[1] * y + s2
        s2 = b[2] * x - a[2] * y
        hpf_out[i] = y

    # 3. Hum Check (Goertzel at 50 Hz & 60 Hz)
    def goertzel(x, target_f):
        k = int(0.5 + (len(x) * target_f / FS))
        omega = (2.0 * np.pi * k) / len(x)
        coeff = 2.0 * np.cos(omega)
        s_prev, s_prev2 = 0.0, 0.0
        for val in x:
            s = val + coeff * s_prev - s_prev2
            s_prev2 = s_prev
            s_prev = s
        return np.sqrt(s_prev**2 + s_prev2**2 - coeff * s_prev * s_prev2 + 1e-12)

    total_rms = np.sqrt(np.mean(hpf_out**2) + 1e-12)
    hum50 = goertzel(hpf_out[:min(len(hpf_out), 32000)], 50.0) / (total_rms * len(hpf_out[:min(len(hpf_out), 32000)]) + 1e-6)
    
    filtered = hpf_out
    if hum50 > 0.15:
        # Apply 50 Hz Notch
        fc_n = 50.0
        q_n = 8.0
        w_n = 2.0 * np.pi * fc_n / FS
        cos_n = np.cos(w_n)
        alpha_n = np.sin(w_n) / (2.0 * q_n)
        a0_n = 1.0 + alpha_n
        b_n = np.array([1.0/a0_n, (-2.0*cos_n)/a0_n, 1.0/a0_n])
        a_n = np.array([1.0, (-2.0*cos_n)/a0_n, (1.0 - alpha_n)/a0_n])
        notch_out = np.zeros_like(filtered)
        s1, s2 = 0.0, 0.0
        for i in range(len(filtered)):
            x = filtered[i]
            y = b_n[0] * x + s1
            s1 = b_n[1] * x - a_n[1] * y + s2
            s2 = b_n[2] * x - a_n[2] * y
            notch_out[i] = y
        filtered = notch_out

    # 4. BS.1770 Gated Loudness Normalization to -20 LUFS
    # Block size = 400ms (6400 samples), hop = 100ms (1600 samples)
    block_sz = 6400
    hop_sz = 1600
    block_powers = []
    for st in range(0, len(filtered) - block_sz + 1, hop_sz):
        block = filtered[st:st+block_sz]
        p = np.mean(block**2)
        if p > 1e-7: # -70 dBFS absolute gate
            block_powers.append(p)
    
    if len(block_powers) > 0:
        mean_p = np.mean(block_powers)
        rel_thresh = mean_p * 0.1 # -10 dB relative gate
        gated_powers = [p for p in block_powers if p >= rel_thresh]
        if len(gated_powers) > 0:
            loudness_p = np.mean(gated_powers)
            lufs = -0.691 + 10.0 * np.log10(loudness_p + 1e-12)
            target_lufs = -20.0
            gain_db = np.clip(target_lufs - lufs, -12.0, 18.0)
            gain = 10.0 ** (gain_db / 20.0)
            filtered = filtered * gain

    # 5. Soft-Knee Tanh Limiter
    threshold = 0.85
    ceiling = 0.98
    lim_out = np.zeros_like(filtered)
    for i in range(len(filtered)):
        val = filtered[i]
        abs_v = abs(val)
        if abs_v <= threshold:
            lim_out[i] = val
        else:
            excess = abs_v - threshold
            ratio = excess / (ceiling - threshold)
            compressed = threshold + (ceiling - threshold) * np.tanh(ratio)
            lim_out[i] = np.sign(val) * min(compressed, ceiling)
    
    return lim_out

# ==================== RECOGNIZER & BENCHMARK ====================

def run_benchmark(wav_file, max_duration=28.0):
    print("=" * 80)
    print(f"BENCHMARKING ABLATION ON: {wav_file}")
    print("=" * 80)

    raw_audio = read_wav(wav_file)
    if max_duration and len(raw_audio) > max_duration * FS:
        raw_audio = raw_audio[:int(max_duration * FS)]
        print(f"Sliced to first {max_duration:.1f}s for Whisper single-window evaluation")
    duration = len(raw_audio) / FS
    print(f"Duration: {duration:.2f} seconds ({len(raw_audio)} samples @ 16 kHz)")

    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder="tools/models/hinglish-swift/hinglish-swift-encoder.int8.onnx",
        decoder="tools/models/hinglish-swift/hinglish-swift-decoder.int8.onnx",
        tokens="tools/models/hinglish-swift/hinglish-swift-tokens.txt",
        language="en",
        task="transcribe",
        num_threads=2
    )

    pipelines = {
        "Branch A (Raw Audio)": raw_audio,
        "Branch B (Old DSP)": old_dsp(raw_audio),
        "Branch C (New DSP)": new_dsp(raw_audio)
    }

    results = {}
    for name, samples in pipelines.items():
        t0 = time.time()
        stream = recognizer.create_stream()
        stream.accept_waveform(FS, samples)
        recognizer.decode_stream(stream)
        t_elapsed = time.time() - t0
        text = stream.result.text.strip()
        results[name] = {
            "text": text,
            "latency": t_elapsed,
            "rtf": t_elapsed / duration,
            "rms": np.sqrt(np.mean(samples**2)),
            "peak": np.max(np.abs(samples))
        }

    for name, res in results.items():
        print(f"\n--- {name} ---")
        print(f"RMS: {res['rms']:.4f} | Peak: {res['peak']:.4f} | Decoded in {res['latency']:.2f}s (RTF: {res['rtf']:.3f}x)")
        print(f"Transcript: \"{res['text']}\"")
        print(f"Word count: {len(res['text'].split())} words, {len(res['text'])} chars")

    return results

if __name__ == "__main__":
    run_benchmark("tools/23.wav")
    print("\n" + "#" * 80 + "\n")
    run_benchmark("tools/198.wav")
    print("\n" + "#" * 80 + "\n")
    run_benchmark("tools/22.wav")

