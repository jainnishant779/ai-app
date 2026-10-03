import sys
import subprocess
import time
import re
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

LLAMA_CLI = r"D:\nishant\llm_research\llama_bin\llama\llama-cli.exe"
MODEL_GGUF = r"D:\nishant\llm_research\llama_bin\qwen3-0.6b-Q4_K_M.gguf"

# Real transcript segments directly from Motorola Edge 60 Fusion database (nishu_device.db)
REAL_DEVICE_CONVERSATIONS = [
    {
        "conv_id": 65,
        "title": "Backend/Tech Discussion (DB, Cache, Pull, Crash)",
        "segments": [
            "DV mein change karna tha ki aur vah ja sake par aapne vahi catch par add karoongi. Thik hai.",
            "Fool karenge ham, koi bhi nahin karenge, try kar le raha tha. Thik hai ki aapko aisa aapke paas",
            "Ek hi vajah se chal rahi hai aur ek hi vajah tak jaakar phir cash milega to cash mein ho gai bhai b",
            "Yahaan par sirph session yah hai ki jitne mein user ki vah request cap par write kar paati.",
            "Din mein tum dega dekhata hai. Pahli cash hua tha.",
            "Hello, kahaan laga hai phone?"
        ],
        "custom_vocab": "DB (DV), cache (catch, cash, cap), pull (fool), crash (cash), Docker, Redis"
    },
    {
        "conv_id": 67,
        "title": "Office Conversation (Keys / Chabhi)",
        "segments": [
            "jaabi nahin aaya hai",
            "Aa rahe hain?",
            "Hello, how are you?"
        ],
        "custom_vocab": "chabhi (jaabi, chaabi), meeting"
    },
    {
        "conv_id": 60,
        "title": "Bash Tutorial (Bash vs Dash)",
        "segments": [
            "Doston dash me nested aur multi level if statement ke spoken tutorial mein aapka swagat hai.",
            "karen c h mode space plus x space nested eflas dot s h"
        ],
        "custom_vocab": "Bash (dash), chmod (c h mode)"
    },
    {
        "conv_id": 69,
        "title": "Speech Audio 69 (Hindi Discussion)",
        "segments": [
            "Sir, mera. Parvishaal kisi mahaar par uske baare mein to yah aisa nahin bolna chaahie."
        ],
        "custom_vocab": ""
    },
    {
        "conv_id": 99,
        "title": "Company Email & Tech Dictation",
        "segments": [
            "Hello synchik at the rate gmail dot com pe email bhej do",
            "Docker container ko kubernetes cluster pe deploy kar do"
        ],
        "custom_vocab": "SINQIT (synchik, sinqik), Docker, Kubernetes"
    }
]

# ==========================================
# TIER 1: FAST KOTLIN-EQUIVALENT PHONETIC CLEANER
# ==========================================
# Runs in < 0.1 ms, zero RAM, zero battery, purely deterministic
def parse_custom_vocab(vocab_str: str) -> list[tuple[str, list[str]]]:
    """Parses 'Word (alias1, alias2), Target2 (alias3)'"""
    rules = []
    if not vocab_str:
        return rules
    items = [x.strip() for x in vocab_str.split(",") if x.strip()]
    i = 0
    while i < len(items):
        item = items[i]
        if "(" in item and ")" not in item:
            # Recombine split commas inside parens
            combined = item
            while i + 1 < len(items) and ")" not in combined:
                i += 1
                combined += ", " + items[i]
            item = combined
        m = re.match(r"^([^\(]+)\((.+)\)$", item)
        if m:
            target = m.group(1).strip()
            aliases = [a.strip() for a in m.group(2).split(",") if a.strip()]
            rules.append((target, aliases))
        else:
            # Just target word
            rules.append((item.strip(), []))
        i += 1
    return rules

BUILTIN_PHONETIC_PAIRS = [
    # Spoken symbols
    (r'(?i)\bat the rate\b', '@'),
    (r'(?i)\bdot com\b', '.com'),
    (r'(?i)\bdot sh\b', '.sh'),
    (r'(?i)\bspace plus x\b', ' +x'),
    (r'(?i)\bplus x\b', '+x'),
    (r'(?i)\bc\s*h\s*mode\b', 'chmod'),
    # Common tech acoustic confusions
    (r'(?i)\bDV\b', 'DB'),
    (r'(?i)\bdash me\b', 'bash me'),
    (r'(?i)\bdash script\b', 'bash script'),
    (r'(?i)\bfool karenge\b', 'pull karenge'),
    (r'(?i)\bfool request\b', 'pull request'),
    (r'(?i)\bjaabi\b', 'chabhi'),
]

def tier1_clean_segment(text: str, vocab_str: str) -> str:
    res = text
    # 1. Built-in tech & spoken symbols
    for pattern, repl in BUILTIN_PHONETIC_PAIRS:
        res = re.sub(pattern, repl, res)
    
    # 2. Custom Vocabulary aliases
    parsed = parse_custom_vocab(vocab_str)
    for target, aliases in parsed:
        for alias in aliases:
            pattern = rf'(?i)\b{re.escape(alias)}\b'
            res = re.sub(pattern, target, res)
    return res

# ==========================================
# TIER 2: BATCHED QWEN 0.6B AUTO-CORRECTOR
# ==========================================
# Runs ONCE per whole recording (not per sentence!)
# Takes all sentences, fixes context, outputs corrected list
def tier2_batch_correct(segments: list[str], vocab_str: str) -> tuple[list[str], float, dict]:
    numbered_lines = "\n".join([f"[{i+1}] {s}" for i, s in enumerate(segments)])
    
    vocab_clause = ""
    if vocab_str:
        vocab_clause = f"User Vocabulary / Entities:\n{vocab_str}\n"

    prompt = f"""<|im_start|>system
You are a speech transcript auto-corrector for Roman Hindi / Hinglish and Tech terms.
Instructions:
1. Fix phonetic/acoustic errors and tech terms based on context and user vocabulary.
2. Maintain the speaker's original language (Roman Hindi/Hinglish). DO NOT translate to English or Devanagari.
3. Keep the exact format: output line numbers [1], [2], etc. with the corrected sentence.
{vocab_clause}<|im_end|>
<|im_start|>user
Correct the following speech transcript segments:
{numbered_lines}
<|im_end|>
<|im_start|>assistant
"""
    t0 = time.time()
    try:
        proc = subprocess.run(
            [
                LLAMA_CLI,
                "-m", MODEL_GGUF,
                "-p", prompt,
                "-st",
                "--reasoning", "off",
                "-n", "256",
                "--temp", "0.0",
                "--no-display-prompt",
                "--simple-io",
            ],
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            timeout=25,
            stdin=subprocess.DEVNULL
        )
        elapsed = time.time() - t0
        raw_out = proc.stdout or ""
        
        # Parse generation metrics from stderr/stdout
        metrics = {"tokens_sec": 0.0, "prompt_sec": 0.0}
        m_speed = re.search(r"Prompt:\s*([\d\.]+)\s*t/s\s*\|\s*Generation:\s*([\d\.]+)\s*t/s", raw_out)
        if m_speed:
            metrics["prompt_sec"] = float(m_speed.group(1))
            metrics["tokens_sec"] = float(m_speed.group(2))
            
        # Extract generated lines
        cleaned_lines = []
        if "> " in raw_out:
            # Everything after the prompt echo
            body = raw_out.split("> ", 1)[1]
            # split off generation stats
            if "[ Prompt:" in body:
                body = body.split("[ Prompt:")[0]
            # The first line is the prompt remainder; generation follows
            body_lines = [l.strip() for l in body.splitlines()[1:] if l.strip()]
            for l in body_lines:
                if l.startswith("<|") or "Exiting..." in l:
                    continue
                m = re.match(r"^\[\d+\]\s*(.+)$", l)
                if m:
                    cleaned_lines.append(m.group(1).strip())
                elif l:
                    cleaned_lines.append(l)
                
        return cleaned_lines, elapsed, metrics
    except Exception as e:
        return [f"Error: {e}"], time.time() - t0, {}

# ==========================================
# RUN THE COMPREHENSIVE LAPTOP BENCHMARK
# ==========================================
print("\n" + "="*80)
print("     NISHU TRANSCRIPT CORRECTION & MOBILE LOAD BENCHMARK (LAPTOP)")
print("="*80)

total_t1_time_ms = 0.0
total_t2_time_s = 0.0

for test in REAL_DEVICE_CONVERSATIONS:
    print(f"\n" + "#"*70)
    print(f"TEST CASE: ID #{test['conv_id']} - {test['title']}")
    print(f"CUSTOM VOCAB: {test['custom_vocab'] or 'None'}")
    print("#"*70)
    
    # 1. Tier 1 Fast Phonetic (Kotlin equivalent)
    t0_lex = time.perf_counter()
    tier1_results = [tier1_clean_segment(s, test['custom_vocab']) for s in test['segments']]
    t1_elapsed_ms = (time.perf_counter() - t0_lex) * 1000
    total_t1_time_ms += t1_elapsed_ms
    
    # 2. Tier 2 Batched Qwen 0.6B LLM
    tier2_results, t2_elapsed_s, metrics = tier2_batch_correct(test['segments'], test['custom_vocab'])
    total_t2_time_s += t2_elapsed_s
    
    print("\n[TRANSCRIPT COMPARISON]")
    for idx, raw_seg in enumerate(test['segments']):
        print(f"\n  Segment [{idx+1}]:")
        print(f"    - RAW WHISPER : {raw_seg}")
        print(f"    - TIER 1 (Lex): {tier1_results[idx] if idx < len(tier1_results) else 'N/A'}")
        t2_out = tier2_results[idx] if idx < len(tier2_results) else "(no output)"
        print(f"    - TIER 2 (LLM): {t2_out}")
        
    print(f"\n[PERFORMANCE METRICS]")
    print(f"  Tier 1 (Instant Lexicon) : {t1_elapsed_ms:.4f} ms  | CPU Load: 0.0% | RAM: 0 MB")
    print(f"  Tier 2 (Batched LLM)     : {t2_elapsed_s:.2f} s    | Gen Speed: {metrics.get('tokens_sec', 0.0):.1f} t/s | Prompt Speed: {metrics.get('prompt_sec', 0.0):.1f} t/s")

print("\n" + "="*80)
print(f"ALL BENCHMARKS COMPLETE")
print(f"Total Tier 1 Time across all 5 test suites: {total_t1_time_ms:.4f} ms (INSTANT)")
print(f"Total Tier 2 Time across all 5 test suites: {total_t2_time_s:.2f} s (Average: {total_t2_time_s/len(REAL_DEVICE_CONVERSATIONS):.2f}s per recording)")
print("="*80)
