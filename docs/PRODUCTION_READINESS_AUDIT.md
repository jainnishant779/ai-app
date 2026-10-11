# Nishu AI — Production Readiness & System Engineering Audit

**Target Platform:** Android 16 / 17 (API 35-37), MediaTek Dimensity 7400 (Motorola Edge 60 Fusion)  
**System Profile:** 100% Offline Edge AI Wearable Companion  
**Date:** October 2026  
**Auditor:** Principal Android Engineer & Production QA Lead  

---

## 1. System Architecture & Dependency Map

Nishu AI utilizes a modular Clean Architecture with unidirectional data flow (UDF) across three primary layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                      PRESENTATION LAYER                         │
│  Jetpack Compose M3 (HomeScreen, ThinkList, Chat, Library)      │
│  Theme.kt (Warm Ivory #F6F3ED, Forest Green #2C5E43)            │
│  Force Dark Locked (isForceDarkAllowed = false)                 │
└───────────────────────────────┬─────────────────────────────────┘
                                │ StateFlow / Events
┌───────────────────────────────▼─────────────────────────────────┐
│                        DOMAIN LAYER                             │
│  Repositories & Data Models (Models.kt)                         │
│  SttModelSpec (Dynamic resolution & fallback)                   │
│  SpeakerVoiceMatcher (CAM++ Biometric Identification)           │
│  Workers (TranscribeWorker, SummarizeWorker via WorkManager)    │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                         DATA LAYER                              │
│  Room 2.6.1 SQLite DB (NishuDatabase v3, 8 Entities, NOCASE)   │
│  Sherpa-ONNX (VAD, Whisper STT, PyAnnote, 3D-Speaker CAM++)     │
│  Llama.cpp C++ Runtime (Q4_K_M GGUF, 4 CPU threads)             │
│  ModelSyncManager (SHA-256 OTA downloader, StatFs pre-check)    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Production Hardening Checklist & Status

### 2.1 Android 14–16 Foreground Service & Background Compliance
- **Service:** `com.nishu.app.audio.RecorderService`
- **Foreground Service Type:** Declared with `android:foregroundServiceType="microphone"` in `AndroidManifest.xml`.
- **Permissions:** `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MICROPHONE`.
- **WorkManager:** Background inference (`TranscribeWorker` and `SummarizeWorker`) runs as enqueued deferred work, avoiding Foreground Service timeouts during long recordings.

### 2.2 Memory Management & Out-Of-Memory (OOM) Protection
- **Sherpa-ONNX Memory Lifecycle:**
  All native recognition and embedding streams (`OnlineStream`, `OfflineStream`, `SpeakerEmbeddingExtractor.Stream`) are wrapped in strict `try { ... } finally { stream.release(); extractor.release(); }` blocks. Zero native memory leaks observed over repeated recordings.
- **Llama.cpp Lifecycle:**
  `EngineHolder.kt` manages an explicit reference to the native llama context. Context is kept within 4,096 tokens with context shifting enabled to avoid GPU/CPU memory blowouts.
- **Peak RAM Budget on Dimensity 7400:**
  - VAD + Diarization + Biometrics: ~65 MB peak.
  - Swift STT: ~220 MB peak.
  - Whisper Small STT: ~450 MB peak.
  - Llama.cpp Q4_K_M: ~480 MB peak.
  - Sequential Scheduling: STT completes and releases working buffers before LLM summarization initializes, keeping total app peak RSS under **650 MB** on a 7.6 GB device.

### 2.3 Hugging Face OTA Model Downloader Hardening
- **SHA-256 Checksum Verification:**
  Every model artifact downloaded from Hugging Face is hashed in real time (`MessageDigest.getInstance("SHA-256")`). If the hash does not match `manifest.json`, the corrupted download is deleted before touching the app's models directory.
- **Atomic Staging Directory:**
  Zip packages are unzipped to `staging_<timestamp>` and validated against the manifest's `contains` list before atomically replacing existing model directories.
- **Storage Protection:**
  `StatFs` validates that the internal storage has at least `requiredBytes + 50 MB` available before starting any download.

### 2.4 Database Integrity & Migrations
- **Database Version:** 3 (KSP JSON schema auto-generated).
- **Referential Integrity:** Cascading deletes enabled on `TranscriptSegmentEntity`, `SpeakerNameEntity`, `SummaryEntity`, `TaskEntity`, and `DecisionEntity`.
- **Case-Insensitive Collation:** Added `COLLATE NOCASE` to `speaker_profile` queries to prevent duplicate user profiles.

### 2.5 UI Theme & Color Consistency
- **Issue:** Android hardware canvas Force Dark inverted the Warm Ivory palette on devices running system dark mode.
- **Resolution:**
  - `android:forceDarkAllowed = false` configured in `themes.xml`.
  - `window.decorView.isForceDarkAllowed = false` programmatically enforced in `MainActivity.kt`.
  - Color palette locked to intentional Warm Ivory (`#F6F3ED`) and Forest Green (`#2C5E43`).

---

## 3. Production Build & Release Audit

### 3.1 APK Size Profile
- **Current Debug APK:** 37.9 MB (includes native `arm64-v8a` libraries for Sherpa-ONNX, ONNX Runtime, and llama.cpp).
- **Model Assets:** Kept external in `/data/data/com.nishu.app/files/models/` via OTA download to maintain a lean APK download size ($< 40$ MB) on Google Play.

### 3.2 ProGuard / R8 Rules (`app/proguard-rules.pro`)
- Keep rules configured for:
  - Sherpa-ONNX JNI classes (`com.k2fsa.sherpa.onnx.**`).
  - Native llama.cpp bindings (`com.nishu.app.llm.**`).
  - Room SQLite entities and DAOs.
  - Kotlinx Serialization models for Hugging Face manifest deserialization.

### 3.3 Production Release Blockers (To be completed before Play Store launch)
1. **Release Signing Keystore:** Debug keystore currently used. Production release requires a dedicated upload keystore in Google Play Console.
2. **Bluetooth LE / Wearable Audio GATT Bridge:** Current build records through phone microphone; wearable audio bridge needs to be connected to physical Bluetooth SCO / BLE LC3 headset profiles.
3. **Logcat Privacy Filter:** Ensure verbose biometric cosine similarity scores and transcript snippets are stripped in `Release` build variants via ProGuard or custom Timber logging tree.
