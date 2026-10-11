# Nishu AI — Baseline and Repository Audit Report

**Date of Audit:** October 11, 2026  
**Auditor:** Principal Android Engineer & On-Device ML/DSP Lead  
**Working Directory:** `d:\nishu-android`  
**Git Baseline Commit:** `d89b4ba43a705525759fe66471bfeeeb4a73a85e`  

---

## 1. Environment & Hardware Specifications

### 1.1 Developer Environment & Toolchain
- **Operating System:** Windows 11
- **Gradle Version:** Gradle 8.10
- **Android Gradle Plugin (AGP):** 8.7.3
- **Kotlin Toolchain:** Kotlin 2.0.21 / KSP 2.0.21-1.0.28
- **JDK:** OpenJDK 17 (`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`, version 17.0.10+7-LTS)
- **Android NDK:** `27.2.12479018`
- **Compile SDK:** 35
- **Target SDK:** 34
- **Min SDK:** 26
- **Native ABI Filter:** `arm64-v8a`

### 1.2 Connected Device Hardware (Verified Live via ADB)
- **Device Identifier:** `ZA222ZJMQG`
- **Marketing Name:** Motorola Edge 60 Fusion
- **SoC Platform:** MediaTek Dimensity 7300/7400 family (`mt6878`, 4x Cortex-A78 @ 2.5 GHz + 4x Cortex-A55 @ 2.0 GHz)
- **OS Version:** Android 17 / SDK 37 (Preview / Canary Environment)
- **Total Physical Memory (RAM):** `7,593,848 kB` (~7.6 GB LPDDR5)
- **Inference Acceleration:** MediaTek NPU 655 / Mali-G615 MC2 GPU / 4 CPU Performance Cores

---

## 2. Artifacts & Binary Profile

### 2.1 Compiled Application Binary
- **Debug APK Location:** `app/build/outputs/apk/debug/app-debug.apk`
- **Actual Size on Disk:** 37,929,565 bytes (**37.9 MB**)
- **Native Libraries Embedded:**
  - `libsherpa-onnx-jni.so` (1.13.8)
  - `libonnxruntime.so`
  - `libllama.so` (Native C++ release build)
  - `libggml.so`

### 2.2 Model Artifacts on Device (`/data/data/com.nishu.app/files/models/`)
Verified directly through `adb shell run-as com.nishu.app ls -lh`:

| Model Subsystem | Artifact Name | Format | Size on Device | Role |
|---|---|---|---|---|
| **Local LLM** | `llm/model.gguf` | Q4_K_M GGUF | **378 MB** (396,704,544 bytes) | Summarization, tasks, decisions, memory |
| **VAD** | `stt/silero_vad.onnx` | ONNX FP32 | **628 KB** (643,854 bytes) | Speech/silence frame detection |
| **Speaker Biometrics** | `stt/diar/3dspeaker-campplus-zh-en.onnx` | ONNX FP32 | **27.0 MB** | 512-dim voiceprint embedding extractor |
| **Diarization** | `stt/diar/pyannote-segmentation-3-0.int8.onnx`| ONNX INT8 | **1.4 MB** | Speaker turn segmentation |
| **STT Baseline (Swift)**| `stt/hinglish-swift/hinglish-swift-decoder.int8.onnx`<br>`stt/hinglish-swift/hinglish-swift-encoder.int8.onnx`<br>`stt/hinglish-swift/hinglish-swift-tokens.txt` | ONNX INT8 | **153 MB total**<br>(Decoder: 125 MB, Encoder: 28 MB, Tokens: 798 KB) | Primary Roman Hinglish speech recognition |
| **STT High Acc (Small)**| `stt/whisper-small/small-decoder.int8.onnx`<br>`stt/whisper-small/small-encoder.int8.onnx`<br>`stt/whisper-small/small-tokens.txt` | ONNX INT8 | **358 MB total**<br>(Decoder: 250 MB, Encoder: 107 MB, Tokens: 798 KB) | High-accuracy multilingual/accented recognition |

---

## 3. Subsystem Architecture Map & Existing Gaps

### 3.1 Audio Capture & Resampling Pipeline
- **Implementation:** `RecorderService.kt` captures 16,000 Hz, 16-bit PCM mono via `MediaRecorder.AudioSource.MIC`.
- **WAV Ingestion:** `WavFile.kt` encodes standard RIFF headers.
- **Resampling:** `AudioImporter.kt` and `Resampler.kt` convert imported non-16kHz files using linear interpolation.
- **Known Gap:** Naive linear resampling lacks polyphase anti-aliasing decimation, which can fold frequencies $> 8$ kHz back into the band when importing 44.1/48 kHz audio.

### 3.2 Wearable / BLE Connectivity
- **Implementation:** UI card states `"Nishu Wearable • Ready"`.
- **Known Gap (Documented):** Currently, audio capture is hardcoded to phone microphone. The wearable status in `RealSettingsRepository.kt` is a mocked configuration toggle. No native `BluetoothHeadset` / `AudioManager.setCommunicationDevice()` or BLE GATT packet streaming service exists yet.

### 3.3 Speech-to-Text Pipeline (`SherpaOnnxStt.kt`, `SttModelSpec.kt`)
- **Engine:** `sherpa-onnx` offline Whisper recognizer.
- **Model Resolution:** `SttModelSpec.select()` dynamically resolves models from `files/models/stt/`, supporting automatic fallback and manual override.
- **Text Post-Processing:** `SttText.clean()` removes common hallucination tokens (`[Music]`, `Thank you for watching`).
- **Known Gap:** `HinglishConverter.kt` is not connected to the live transcription stream.

### 3.4 Biometric Voice Identification (`SpeakerVoiceMatcher.kt`)
- **Extraction:** 3D-Speaker CAM++ extracts 512-dimensional unit vectors.
- **Audio Windowing:** `selectBestSpeechSamples()` extracts high-energy 500ms windows up to 15s, discarding leading/trailing silence.
- **Threshold:** Calibrated to `0.50f` (single profile: `0.46f`).
- **Vector Math:** L2 normalization enforced after extraction and running-average merge.
- **Database Collation:** `COLLATE NOCASE` enabled in `SpeakerProfileDao` to prevent case mismatch duplicates.

### 3.5 Hugging Face OTA Downloader (`ModelSyncManager.kt`)
- **Remote Repo:** `nishantj-123/Nishant_finetune`.
- **Integrity:** SHA-256 validation computed via `MessageDigest` before extraction.
- **Installation:** Atomic extraction into `staging_<timestamp>` directory with directory replacement to prevent half-installed models on network failure.
- **Storage Pre-check:** `StatFs` validates required space + 50MB safety margin before starting download.

### 3.6 Local LLM Execution (`EngineHolder.kt`)
- **Engine:** `llama.cpp` JNI wrapper (`libllama.so`).
- **Model:** `llm/model.gguf` (Qwen2.5 0.5B fine-tuned, 4,096 context window).
- **Extraction:** Structured JSON prompts parsed by `ToolCallParser.kt` for summaries, tasks, decisions, and memory facts.

### 3.7 Room Database Schema (`NishuDatabase.kt`)
- **Database Version:** 3 (KSP JSON schema generated at `schemas/com.nishu.app.data.db.NishuDatabase/3.json`).
- **Entities:** `ConversationEntity`, `TranscriptSegmentEntity`, `SpeakerNameEntity`, `SpeakerProfileEntity`, `SummaryEntity`, `TaskEntity`, `DecisionEntity`, `MemoryFactEntity`.

### 3.8 UI Theme & Dark Mode Stability
- **Color Palette:** Warm Ivory (`#F6F3ED`) background, Forest Green (`#2C5E43`) primary, Mint (`#52B788`) accents.
- **Force Dark Lock:** `android:forceDarkAllowed = false` in `themes.xml` and `window.decorView.isForceDarkAllowed = false` in `MainActivity.kt`.

---

## 4. Test Suite Baseline

- **Total Test Classes:** 14
- **Total Unit Tests Executed:** 83
- **Passed:** 83 (100%)
- **Failed:** 0
- **Execution Time:** ~31s
