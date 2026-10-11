# Nishu AI — Privacy, Security & Data Governance Audit

**System Classification:** Offline-First Ambient AI Companion & Biometric Voice Vault  
**Operating System:** Android 16 / 17  
**Compliance Standards:** Privacy by Design, Zero-Cloud Telemetry, Indian Digital Personal Data Protection (DPDP) Act & GDPR Alignment  
**Date:** October 2026  
**Auditor:** Principal Security Engineer & Privacy Lead  

---

## 1. Privacy Posture & On-Device Data Guarantees

### 1.1 Complete Air-Gapped Offline Operation
Nishu AI operates under a **Zero-Cloud Architecture**:
- **Speech-to-Text:** All audio recorded through the microphone is transcribed on-device using local ONNX runtimes (`sherpa-onnx`). No audio streams or audio chunks are ever sent to external transcription APIs.
- **Biometric Voiceprints:** Speaker embeddings are extracted locally using 3D-Speaker CAM++ and matched inside the local SQLite database. No voice biometric data is ever shared with external servers.
- **Large Language Model:** All summarization, action-item extraction, decision logging, and memory retrieval are executed on the device's CPU performance cores using native `llama.cpp` and a local Q4_K_M GGUF model.
- **Conversational Memory:** Chat contexts and memory facts are stored exclusively inside `/data/data/com.nishu.app/databases/nishu.db`.

### 1.2 Network Traffic & Telemetry Audit
- **Zero Third-Party Analytics:** The application contains **zero** instances of Firebase Analytics, Google Analytics, Mixpanel, AppsFlyer, Sentry, or Crashlytics.
- **Network Surface:** The network stack is active **only** during explicit user-initiated model downloads from Hugging Face (`ModelSyncManager.kt`). Once models are installed, the app can operate in permanent airplane mode with zero feature degradation.

---

## 2. Biometric Data Protection & Security Controls

### 2.1 Non-Invertibility of Voiceprints
- The 3D-Speaker CAM++ model maps speech waveforms into a 512-dimensional continuous latent space.
- **Security Property:** These 512-float embeddings are non-invertible mathematical projections. A mathematical embedding vector cannot be reversed or synthesized back into raw speech, voice timbre, or personal words.
- Raw PCM audio buffers used during enrollment are discarded from RAM as soon as the vector is extracted.

### 2.2 Local Data Sandboxing
- Voiceprints are stored as comma-separated floating-point strings in the `speaker_profile` table of `nishu.db`.
- The database is located in the app's private sandbox directory:
  ```text
  /data/data/com.nishu.app/databases/nishu.db
  ```
  On non-rooted Android devices, this directory is protected by Android SElinux application sandbox policies and cannot be read by other installed applications.

### 2.3 User Rights & Biometric Deletion
- **Explicit Erasure:** Users can view, rename, and permanently delete their enrolled voiceprint at any time from **Settings → Manage Voice Profiles**.
- Deleting a voice profile deletes the embedding row from the database and removes all biometric associations from future meetings.

---

## 3. Application Security & Vulnerability Defense

### 3.1 Zip Slip / Path Traversal Defense
- **Attack Vector:** A maliciously crafted model zip archive containing relative directory traversal paths (`../../../../system/bin/malicious`) could overwrite sensitive system or app files during extraction.
- **Mitigation Implemented in `ModelSyncManager.kt`:**
  ```kotlin
  val newFile = File(targetDir, entry.name)
  if (!newFile.canonicalPath.startsWith(canonicalTarget)) {
      throw SecurityException("Zip traversal exploit detected: ${entry.name}")
  }
  ```
  Every zip entry is verified to ensure its canonical path strictly resides within the target directory.

### 3.2 Integrity Validation via SHA-256 Checksums
- To protect against Man-in-the-Middle (MITM) tampering or corrupted byte transfers during model updates, all model files downloaded from Hugging Face are cryptographically hashed using SHA-256 before installation. Mismatched files are discarded immediately.

### 3.3 Android Permissions Audit

| Permission | Protection Level | Purpose | Justification |
|---|---|---|---|
| `android.permission.RECORD_AUDIO` | Dangerous (Runtime) | Capture audio from microphone | Core functionality |
| `android.permission.POST_NOTIFICATIONS`| Dangerous (Runtime) | Display foreground recording status | Required on Android 13+ |
| `android.permission.FOREGROUND_SERVICE`| Normal | Run continuous background recording | Android 14+ FGS compliance |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Normal | Declare microphone foreground service | Android 14+ FGS compliance |
| `android.permission.INTERNET` | Normal | Optional download of open-source models | Hugging Face model sync |

**Unused Dangerous Permissions:** Zero requests for `CAMERA`, `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, `READ_CALENDAR`, or `READ_EXTERNAL_STORAGE`.

---

## 4. Threat Model & Risk Mitigation Matrix

| Threat | Likelihood | Impact | Built-in Mitigation |
|---|---|---|---|
| **Eavesdropping on Audio** | Low | Critical | Audio processed entirely in RAM and sandboxed private storage |
| **Tampered Model Supply Chain** | Low | High | SHA-256 cryptographic verification against manifest |
| **Corrupted Download Disk Exhaustion**| Medium | Medium | Android `StatFs` check verifying $\ge 50$ MB free space buffer |
| **Impostor Voice Hijacking** | Medium | Medium | Biometric threshold calibrated to $0.50$; rejection test harness verified |
| **Malicious Archive Traversal** | Low | High | Canonical path validation in `unzipFile` |
