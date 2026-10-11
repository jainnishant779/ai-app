# Nishu AI — Production Audio Enhancement, Diarization, Overlap & Hinglish STT
## Comprehensive Implementation Plan & Engineering Specification

**Target Hardware:** Motorola Edge 60 Fusion (`ZA222ZJMQG`, MediaTek Dimensity 7400, Android 16/17, 7.6 GB RAM)  
**Baseline Model:** `Oriserve/Whisper-Hindi2Hinglish-Swift` INT8 (Sherpa-ONNX)  
**Diarization Stack:** PyAnnote Segmentation 3.0 INT8 + 3D-Speaker CAM++ 512-dim (Sherpa-ONNX)  
**Biometric Storage:** Room SQLite Database Schema v3  
**Status:** Approved for Implementation (Phases 1–7)

---

## 1. Executive Strategy & Architectural Decisions

### 1.1 The Golden Rule of Speech ASR Preprocessing
Modern neural ASR models (Whisper, Conformer, Qwen-ASR) are trained on uncurated, real-world internet audio with ambient noise, room acoustics, and varied microphones. Aggressive DSP filtering (deep spectral subtraction, sharp comb filtering, or non-linear noise gates) damages subtle formant transitions ($F_1, F_2, F_3$) and cuts unvoiced consonants (/s/, /sh/, /f/, /t/), leading to **higher Word Error Rate (WER)**.

Therefore, our architecture implements **Pipeline F (Hybrid Adaptive Pipeline)**:
1. **Never filter blind:** Audio is first analyzed via a fast single-pass diagnostic engine (`AudioDiagnostics`).
2. **Surgical, Linear-Phase DSP:**
   - 2nd-order Butterworth 80 Hz High-Pass Filter ($Q=0.7071$, Direct Form II Transposed) eliminates desk rumble and mic handling without ringing in human voice fundamental frequencies ($85\text{--}255\text{ Hz}$).
   - Goertzel-detected 50 Hz / 60 Hz hum notch filter ($Q=30$) engages **only** when mains hum is detected.
   - ITU-R BS.1770-4 momentary loudness estimation brings speech frames to $-20\text{ LUFS}$ without blowing up quiet background noise.
   - Soft-knee polynomial ($tanh$) limiter replaces hard clipping (`coerceIn(-0.98, 0.98)`), preventing high-frequency harmonic generation.
3. **Diarization Branching on Clean Timbre:** Speaker embeddings (CAM++) and PyAnnote segmentation run on minimally altered audio to prevent acoustic distortion from splitting speaker clusters.
4. **Conditional Neural Enhancement (RNNoise / DeepFilterNet):** Invoked **only** when measured SNR $< 10\text{ dB}$ under non-stationary noise.
5. **Pragmatic Overlap Handling:** Blind neural source separation (SepFormer/Conv-TasNet) is disqualified due to excessive edge RAM ($>1.2\text{ GB}$), high latency ($\text{RTF} > 2.8\times$), and stream permutation errors. Instead, PyAnnote multi-speaker frame probabilities detect cross-talk intervals, which are attributed to both speakers and decoded with context padding.

---

## 2. Component Architecture & System Flow

```
                               ┌────────────────────────────────────────────────┐
                               │   Incoming 16 kHz Mono PCM16 Audio Recording   │
                               └───────────────────────┬────────────────────────┘
                                                       │
                                                       ▼
                               ┌────────────────────────────────────────────────┐
                               │     Stage 1: AudioDiagnostics (Single-Pass)    │
                               │  - DC Offset (mean amplitude)                  │
                               │  - Noise Floor (10th percentile frame dB)      │
                               │  - Speech Level (75th percentile frame dB)     │
                               │  - Estimated SNR (Speech - Noise Floor)        │
                               │  - Goertzel Hum Detector (50 Hz / 60 Hz)       │
                               │  - Clipping Percentage                         │
                               └───────────────────────┬────────────────────────┘
                                                       │
                                                       ▼
                               ┌────────────────────────────────────────────────┐
                               │   Stage 2: Adaptive DSP Engine (BiquadFilters)  │
                               │  - DC Offset Leaky Integrator (fc = 20 Hz)     │
                               │  - 2nd-Order Butterworth HPF (80 Hz, Q=0.7071) │
                               │  - Conditional Notch (50/60 Hz, Q=30, if hum)  │
                               │  - ITU-R BS.1770-4 Gated Loudness Normalizer   │
                               │  - Soft-Knee Polynomial Limiter (Peak <= 0.98) │
                               └───────────────────────┬────────────────────────┘
                                                       │
                           ┌───────────────────────────┴───────────────────────────┐
                           ▼                                                       ▼
        ┌──────────────────────────────────────┐               ┌──────────────────────────────────────┐
        │   Stage 3: Speaker Diarization       │               │      Stage 4: VAD & STT Engine       │
        │  - PyAnnote Segmentation 3.0 (INT8)  │               │  - Silero VAD (v4 ONNX, thr=0.30)    │
        │  - Overlap Detection (Multi-Speaker) │               │  - 320ms Lead / 250ms Tail Pad       │
        │  - CAM++ 512-dim Embedding (27 MB)   │               │  - SpeechBatcher (25s chunks)        │
        │  - AHC Clustering (Threshold = 0.88) │               │  - Sherpa-ONNX Offline Recognizer    │
        │  - Room DB Schema v3 Cosine Matching │               │    (Swift 72M / Whisper Small 244M)  │
        └──────────────────┬───────────────────┘               └──────────────────┬───────────────────┘
                           │                                                       │
                           └───────────────────────────┬───────────────────────────┘
                                                       │
                                                       ▼
                               ┌────────────────────────────────────────────────┐
                               │     Stage 5: Alignment & Text Post-Processing  │
                               │  - SpeakerTurns.split with Cross-Talk flags    │
                               │  - SttText.clean (hallucination stripping)     │
                               │  - HinglishConverter (Devanagari to Roman)     │
                               │  - Tech term & Custom Vocabulary resolution    │
                               │  - Room DB Persistence (transcripts, speakers) │
                               └────────────────────────────────────────────────┘
```

---

## 3. Mathematical & Algorithmic Specifications

### 3.1 DC Offset Removal
A 1st-order IIR leaky integrator filter removes ADC zero-offset without introducing audible phase delay:
$$y[n] = x[n] - x[n-1] + R \cdot y[n-1], \quad R = 1 - \frac{2\pi \cdot f_c}{f_s} \approx 0.99215 \quad (f_c = 20\text{ Hz}, f_s = 16000\text{ Hz})$$

### 3.2 2nd-Order Biquad Butterworth High-Pass Filter (80 Hz)
Implemented via Direct Form II Transposed to guarantee float32 numerical stability on ARM NEON:
$$y[n] = b_0 x[n] + s_1[n-1]$$
$$s_1[n] = b_1 x[n] - a_1 y[n] + s_2[n-1]$$
$$s_2[n] = b_2 x[n] - a_2 y[n]$$
For $f_c = 80\text{ Hz}, f_s = 16000\text{ Hz}, Q = \frac{1}{\sqrt{2}} \approx 0.7071$:
- $\omega_0 = \frac{2\pi \cdot 80}{16000} \approx 0.031416\text{ rad}$
- $\alpha = \frac{\sin(\omega_0)}{2Q} \approx 0.022213$
- $b_0 = \frac{1 + \cos(\omega_0)}{2 \cdot (1 + \alpha)} \approx 0.97798$
- $b_1 = -\frac{1 + \cos(\omega_0)}{1 + \alpha} \approx -1.95596$
- $b_2 = \frac{1 + \cos(\omega_0)}{2 \cdot (1 + \alpha)} \approx 0.97798$
- $a_1 = -\frac{2\cos(\omega_0)}{1 + \alpha} \approx -1.95507$
- $a_2 = \frac{1 - \alpha}{1 + \alpha} \approx 0.95655$
**Result:** $-12\text{ dB/octave}$ attenuation, $-24\text{ dB}$ at 20 Hz desk rumble, and passband ripple $< 0.05\text{ dB}$ above 120 Hz.

### 3.3 Goertzel Mains Hum Detection & Narrowband Notch Filter
For $k \in \{50\text{ Hz}, 60\text{ Hz}\}$:
$$s[n] = x[n] + 2\cos(\omega_k) s[n-1] - s[n-2]$$
$$|X(k)|^2 = s[N-1]^2 + s[N-2]^2 - 2\cos(\omega_k) s[N-1] s[N-2]$$
If $\frac{|X(50\text{ Hz})|^2}{\frac{1}{2}(|X(45\text{ Hz})|^2 + |X(55\text{ Hz})|^2)} > 4.0\text{ (6 dB)}$, a 2nd-order IIR Notch filter ($Q = 30$, bandwidth $B = 4\text{ Hz}$) is engaged:
$$H(z) = \frac{1 - 2\cos(\omega_0)z^{-1} + z^{-2}}{1 - 2r\cos(\omega_0)z^{-1} + r^2 z^{-2}}, \quad r = 1 - \frac{\pi B}{f_s} \approx 0.999215$$
**Result:** $> 32\text{ dB}$ electrical hum attenuation with $< 0.1\text{ dB}$ impact outside the notch.

### 3.4 ITU-R BS.1770-4 Loudness Normalization & Soft-Knee Limiter
1. **Loudness Calculation:** Gated energy across 400 ms frames targeting $-20\text{ LUFS}$.
2. **Gain Clamping:** Bounded between $-6\text{ dB}$ and $+15\text{ dB}$ to prevent amplifying low-level background noise.
3. **Soft-Knee Polynomial Limiter:**
   $$f(x) = \begin{cases} x, & |x| \le 0.88 \\ \text{sign}(x) \cdot \left(0.88 + 0.10 \cdot \tanh\left(\frac{|x| - 0.88}{0.10}\right)\right), & |x| > 0.88 \end{cases}$$
   Peak amplitude is guaranteed $\le 0.98$, with zero odd-harmonic distortion from hard waveform clipping.

---

## 4. Phase-by-Phase Implementation Roadmap

### Phase 1: Repository Audit & Baseline Verification (Completed)
- Baseline established at Git commit `84b4cf3`.
- Verified 83/83 unit tests passing.
- Model registry and Room DB Schema v3 verified on Motorola Edge 60 Fusion.

### Phase 2: Foundational DSP Engine Components
*Files to Create:*
1. `app/src/main/java/com/nishu/app/audio/AudioDiagnostics.kt`:
   - Computes RMS, peak dBFS, DC offset, noise floor (10th percentile), speech level (75th percentile), estimated SNR, and Goertzel hum ratios.
2. `app/src/main/java/com/nishu/app/audio/BiquadFilters.kt`:
   - Direct Form II Transposed biquad filter.
   - `createHighPass80Hz()`: 2nd-order Butterworth.
   - `detectAndCreateHumNotch()`: Goertzel detector + 50/60Hz notch.
   - State preservation across contiguous streaming chunks.
3. `app/src/main/java/com/nishu/app/audio/LoudnessNormalizer.kt`:
   - Gated momentary loudness calculator.
   - Dynamic soft-knee limiter.

### Phase 3: Audio Preprocessor & STT Pipeline Refactoring
*Files to Update:*
1. `app/src/main/java/com/nishu/app/stt/AudioPreprocessor.kt`:
   - Replace 1-pole IIR with `BiquadFilters` and `LoudnessNormalizer`.
   - Implement `AudioDiagnostics.analyze(wav)` to determine dynamic filter parameters.
   - Maintain chunk continuity state in streaming mode.
2. `app/src/main/java/com/nishu/app/stt/SherpaOnnxStt.kt`:
   - Route audio through new `AudioPreprocessor`.
   - Connect `HinglishConverter.toHinglish()` for models outputting Devanagari script.

### Phase 4: Overlap Detection & Streaming Diarization
*Files to Update:*
1. `app/src/main/java/com/nishu/app/stt/SpeakerDiarizer.kt`:
   - Remove 20-minute hard limit by implementing chunked streaming windowing.
   - Extract multi-speaker frame probabilities from PyAnnote Segmentation 3.0 to identify cross-talk intervals.
2. `app/src/main/java/com/nishu/app/stt/SpeakerTurns.kt`:
   - Add `isOverlap: Boolean` and `secondarySpeaker: Int?` to `SpeakerTurn` and `SpeakerPiece`.
   - Update `SpeakerTurns.split()` to retain overlap annotations and prevent boundary clipping.
3. `app/src/main/java/com/nishu/app/data/db/Entities.kt` & `Daos.kt`:
   - Store overlap metadata in `TranscriptSegmentEntity`.

### Phase 5: Benchmark & Diagnostics Developer UI
*Files to Update:*
1. `app/src/main/java/com/nishu/app/ui/bench/BenchmarkScreen.kt`:
   - Add Audio Diagnostics card (SNR, DC offset, hum detected, peak dBFS).
   - Add DSP pipeline selector toggle (Raw vs Baseline vs Adaptive DSP).
   - Display real-time WER/CER comparison and processing latency.

### Phase 6: Unit Testing & Verification Suite
*Files to Create:*
1. `app/src/test/java/com/nishu/app/audio/AudioDiagnosticsTest.kt`:
   - Verify DC offset calculation, noise floor estimation, and Goertzel 50/60Hz hum detection.
2. `app/src/test/java/com/nishu/app/audio/BiquadFiltersTest.kt`:
   - Test frequency response of 80 Hz Butterworth HPF and 50 Hz notch rejection.
   - Verify zero numerical drift over 100,000 samples.
3. `app/src/test/java/com/nishu/app/audio/LoudnessNormalizerTest.kt`:
   - Verify soft-knee limiter bounds peaks to $\le 0.98$ without hard clipping.

### Phase 7: Live Hardware Validation on Motorola Edge 60 Fusion
- Deploy debug build to device `ZA222ZJMQG`.
- Benchmark 30-minute meeting recording:
  - Measure RTF, CPU temperature delta, and peak PSS memory.
  - Verify zero audio leakage off-device.

---

## 5. File Change Matrix

| File Path | Action | Description of Changes |
|---|---|---|
| `app/src/main/java/com/nishu/app/audio/AudioDiagnostics.kt` | **CREATE** | Single-pass audio analysis (DC offset, SNR, 50/60Hz hum, noise floor) |
| `app/src/main/java/com/nishu/app/audio/BiquadFilters.kt` | **CREATE** | Direct Form II Transposed biquad filters (80Hz Butterworth HPF, Goertzel Notch) |
| `app/src/main/java/com/nishu/app/audio/LoudnessNormalizer.kt` | **CREATE** | ITU-R BS.1770-4 momentary loudness leveling + soft-knee polynomial limiter |
| `app/src/main/java/com/nishu/app/stt/AudioPreprocessor.kt` | **MODIFY** | Upgrade to 2nd-order Biquads, integrate diagnostics, replace hard clipping |
| `app/src/main/java/com/nishu/app/stt/SherpaOnnxStt.kt` | **MODIFY** | Integrate updated preprocessor and wire `HinglishConverter` for Devanagari models |
| `app/src/main/java/com/nishu/app/stt/SpeakerTurns.kt` | **MODIFY** | Add cross-talk / overlap interval annotations to speaker turns |
| `app/src/main/java/com/nishu/app/ui/bench/BenchmarkScreen.kt` | **MODIFY** | Add audio diagnostics and DSP comparison cards to developer screen |
| `app/src/test/java/com/nishu/app/audio/BiquadFiltersTest.kt` | **CREATE** | Unit tests verifying filter attenuation, frequency response, and stability |
| `app/src/test/java/com/nishu/app/audio/AudioDiagnosticsTest.kt` | **CREATE** | Unit tests for SNR, hum detection, and level estimation |

---

## 6. Verification Protocol & Acceptance Criteria

1. **DSP Numerical Stability:**
   - 80 Hz Butterworth HPF must attenuate 20 Hz rumble by $\ge 20\text{ dB}$ while preserving frequencies $\ge 120\text{ Hz}$ with $< 0.1\text{ dB}$ ripple.
   - Goertzel detector must achieve $100\%$ detection of 50 Hz/60 Hz hum with $> 6\text{ dB}$ SNR, and $0\%$ false alarms on clean speech.
   - Soft-knee limiter must clamp all output samples to $[-0.98, +0.98]$ with zero hard clipping.
2. **ASR Quality on Motorola Edge 60 Fusion:**
   - **Noisy Speech (AC / Fan):** Word Error Rate (WER) reduced by $\ge 1.5\text{ percentage points}$ compared to baseline.
   - **Clean Speech:** Zero regression in WER (within statistical margin $\pm 0.2\%$).
   - **Real-Time Factor (RTF):** Total preprocessing overhead $< 0.01\times$ RTF on MediaTek Dimensity 7400.
3. **Biometrics & Speaker Recall:**
   - Diarization and CAM++ embedding match accuracy maintained at $\ge 95\%$ across multi-session recordings.
4. **Offline Privacy:**
   - $100\%$ on-device local execution; zero audio or biometric data leaves the phone.
