# Nishu AI — Speech Recognition (STT) Benchmark Protocol & Evaluation Suite

**Objective:** Standardized edge evaluation protocol for code-switched Hindi-English (Hinglish) transcription on Android devices.  
**Target Hardware:** MediaTek Dimensity 7400 (4x Cortex-A78 @ 2.5 GHz, 4x Cortex-A55 @ 2.0 GHz), 7.6 GB LPDDR5 RAM, Android 16.  
**Runner Script:** `tools/eval_hinglish_candidates.py`  
**Output Records:** `tools/benchmark_results/candidate_benchmark_report.json`  

---

## 1. Evaluation Dataset Composition

To assess real-world performance for wearable meeting recordings, the benchmark suite evaluates four distinct acoustic and semantic categories:

### Case 1: `meeting_dev_01` — Backend Architecture & Tech Standup
- **Duration:** 12.4 seconds
- **Acoustic Environment:** Quiet room, phone placed on conference table (1.5m distance).
- **Linguistic Profile:** High code-switching frequency; dense English software engineering terminology.
- **Ground Truth (Reference):**
  > `"kal client ke saath meeting mein deployment aur database scaling discuss ki unhone AWS lambda aur PostgreSQL performance optimization poocha"`
- **Critical Technical Terms:** `["client", "meeting", "deployment", "database", "scaling", "AWS", "lambda", "PostgreSQL", "performance", "optimization"]`

### Case 2: `meeting_dev_02` — Product Roadmap & Android App Release
- **Duration:** 9.8 seconds
- **Acoustic Environment:** Wearable lapel microphone in office environment.
- **Linguistic Profile:** Colloquial Hinglish with mobile development terms.
- **Ground Truth (Reference):**
  > `"agla sprint plan karna hai jisme offline voice memory feature aur model sync manager push karna hoga"`
- **Critical Technical Terms:** `["sprint", "plan", "offline", "voice", "memory", "feature", "model", "sync", "manager", "push"]`

### Case 3: `casual_chat_03` — Casual Team Discussion & Scheduling
- **Duration:** 8.1 seconds
- **Acoustic Environment:** Natural conversational cadence; varied speech rate.
- **Ground Truth (Reference):**
  > `"haan main do baje tak draft review bhej dunga fir hum final call schedule kar sakte hain"`
- **Critical Technical Terms:** `["draft", "review", "final", "call", "schedule"]`

### Case 4: `wearable_noisy_04` — Wearable Lapel Mic Under Ambient Noise
- **Duration:** 11.2 seconds
- **Acoustic Environment:** Cafe / cafeteria background chatter (SNR $\approx 12$ dB).
- **Linguistic Profile:** Hardware & Bluetooth connectivity discussion.
- **Ground Truth (Reference):**
  > `"bluetooth connectivity issue verify kiya tha wearable background mein audio packets drop nahi kar raha"`
- **Critical Technical Terms:** `["bluetooth", "connectivity", "issue", "verify", "wearable", "background", "audio", "packets", "drop"]`

---

## 2. Mathematical Definition of Evaluation Metrics

### 2.1 Word Error Rate (WER)
Word Error Rate measures the minimum edit operations (Substitutions $S$, Deletions $D$, Insertions $I$) required to transform the hypothesis sequence $H$ into the ground-truth reference sequence $R$:
$$\text{WER} = \frac{S + D + I}{N_R} \times 100\%$$
*Where $N_R$ is the total number of words in the reference transcript. Case and punctuation are normalized prior to calculation.*

### 2.2 Character Error Rate (CER)
Measures character-level Levenshtein distance:
$$\text{CER} = \frac{S_{\text{char}} + D_{\text{char}} + I_{\text{char}}}{N_{\text{chars, } R}} \times 100\%$$

### 2.3 Technical Term Retention (TTR)
Evaluates whether critical English technical vocabulary is preserved intact without phonetic degradation:
$$\text{TTR} = \frac{\sum_{t \in \mathcal{T}} \mathbb{I}(t \in H)}{|\mathcal{T}|} \times 100\%$$
*Where $\mathcal{T}$ is the set of required technical keywords for the test segment.*

### 2.4 Script Consistency Distribution (SCD)
Measures the percentage of characters belonging to the Latin (Roman) alphabet vs Devanagari script:
$$\text{SCD}_{\text{Latin}} = \frac{N_{\text{Latin}}}{N_{\text{Total non-whitespace}}} \times 100\%$$
$$\text{SCD}_{\text{Devanagari}} = \frac{N_{\text{Devanagari}}}{N_{\text{Total non-whitespace}}} \times 100\%$$
*A target Hinglish transcript should achieve $\text{SCD}_{\text{Latin}} = 100\%$.*

### 2.5 Real-Time Factor (RTF)
Measures edge inference latency relative to spoken audio duration:
$$\text{RTF} = \frac{T_{\text{inference}}}{T_{\text{audio duration}}}$$
- $\text{RTF} < 0.3$: **Excellent** (Fast background processing, low battery draw).
- $0.3 \le \text{RTF} < 0.6$: **Acceptable** for high-accuracy background processing.
- $\text{RTF} \ge 1.0$: **Unacceptable** (Inference cannot keep up with real-time speech).

---

## 3. Protocol Execution & Automated Runner

### 3.1 Executing the Benchmark
From the project root:
```powershell
python tools/eval_hinglish_candidates.py
```

### 3.2 Automated Outputs
1. Console summary table containing:
   - Model name, parameter count, INT8/GGUF weight size.
   - Peak RAM (MB) and measured RTF on Dimensity 7400.
   - Script distribution, TTR %, and production recommendation.
2. Machine-readable JSON records: `tools/benchmark_results/candidate_benchmark_report.json`.

---

## 4. Benchmark Results Summary

| Model Candidate | Model Architecture | Size | Peak RAM | RTF (Dimensity 7400) | Script Type | TTR % | Edge Feasibility |
|---|---|---|---|---|---|---|---|
| **Swift Roman Hinglish (Base)** | Whisper Base Fine-tune | 127 MB | 220 MB | **0.22x** | Pure Roman Latin | 100.0% | **Recommended Default** |
| **Whisper Small Multilingual** | Whisper Small | 240 MB | 450 MB | **0.48x** | Latin (`en` mode) | 100.0% | **Recommended (Max Accuracy)** |
| **Zero STT Hinglish** | Whisper Medium Fine-tune | 770 MB | 1,650 MB | 1.15x | Mixed Devanagari | 100.0% | Infeasible (Slow / High RAM) |
| **Whisper Tiny Hinglish** | Whisper Tiny Fine-tune | 46 MB | 95 MB | 0.08x | Pure Roman Latin | 74.2% | Infeasible (Acoustic drops) |
| **Whisper Tiny CT2** | Whisper Tiny CTranslate2 | 46 MB | 90 MB | 0.07x | Pure Roman Latin | 100.0% | Infeasible (Requires 2nd C++ runtime) |
| **Hinglish Conformer-CTC** | NeMo Conformer GGUF | 171 MB | 140 MB | 0.12x | Devanagari Hindi | 100.0% | Infeasible (Devanagari output) |
| **Qwen3-ASR 0.6B** | Audio-LLM Encoder-Decoder | 980 MB | 1,150 MB | 0.72x | Devanagari Hindi | 100.0% | Experimental (Devanagari output) |
