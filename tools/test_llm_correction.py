import sys
import subprocess
import time
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

LLAMA_CLI = r"D:\nishant\llm_research\llama_bin\llama\llama-cli.exe"
MODEL_GGUF = r"D:\nishant\llm_research\llama_bin\qwen3-0.6b-Q4_K_M.gguf"

test_samples = [
    {
        "id": "Sample 1 (Bash CLI)",
        "raw": "Doston dash me nested aur multi level if statement ke spoken tutorial mein aapka swagat hai.",
        "context": "Context: Bash scripting, Linux CLI, Linux commands.",
    },
    {
        "id": "Sample 2 (chmod command)",
        "raw": "karen c h mode space plus x space nested eflas dot s h",
        "context": "Context: chmod, Linux scripts, shell scripting.",
    },
    {
        "id": "Sample 3 (Audio 69 - Real Hindi)",
        "raw": "Sir mera. Parvishaal kisi mahaar par uske baare mein to yah aisa nahin bolna chaahie.",
        "context": "Context: Hindi meeting conversation, workplace discussion.",
    },
    {
        "id": "Sample 4 (Audio 67 - Real Key)",
        "raw": "jaabi nahin aaya hai. Aa rahe hain?",
        "context": "Context: Office conversation, keys and arrival.",
    },
    {
        "id": "Sample 5 (Email dictation)",
        "raw": "Hello synchik at the rate gmail dot com pe email bhej do",
        "context": "Context: User company is SINQIT (synchik, sinqik), email communication.",
    }
]

# --- METHOD A: Tier 1 Fast Phonetic Lexicon ---
def fast_phonetic_clean(text: str, vocab: str) -> str:
    import re
    res = text
    # 1. Spoken tech symbols
    res = re.sub(r'(?i)\bat the rate\b', '@', res)
    res = re.sub(r'(?i)\bdot com\b', '.com', res)
    res = re.sub(r'(?i)\bdot sh\b', '.sh', res)
    res = re.sub(r'(?i)\bspace plus x\b', ' +x', res)
    res = re.sub(r'(?i)\bplus x\b', '+x', res)
    res = re.sub(r'(?i)\bc\s*h\s*mode\b', 'chmod', res)
    # 2. Common Hindi phonetic shifts
    res = re.sub(r'(?i)\bjaabi\b', 'chabhi', res)
    # 3. Custom vocabulary aliases
    res = re.sub(r'(?i)\bsynchik\b', 'SINQIT', res)
    res = re.sub(r'(?i)\bsinqik\b', 'SINQIT', res)
    return res

# --- METHOD B: Qwen 0.6B LLM Single-Pass Corrector ---
def run_qwen_correction(raw_text: str, context: str) -> tuple[str, float]:
    prompt = f"""<|im_start|>system
You are a speech transcript auto-corrector.
Fix phonetic mistakes and spoken punctuation.
DO NOT translate. Keep the exact Roman Hindi / Hinglish words of the speaker.
Output ONLY the corrected transcript, nothing else.<|im_end|>
<|im_start|>user
{context}
Raw: "{raw_text}"
Corrected:<|im_end|>
<|im_start|>assistant
"""
    t0 = time.time()
    try:
        proc = subprocess.run(
            [
                LLAMA_CLI,
                "-m", MODEL_GGUF,
                "-p", prompt,
                "-no-cnv",
                "-n", "60",
                "--temp", "0.1",
                "--no-display-prompt",
                "--simple-io",
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        elapsed = time.time() - t0
        out = proc.stdout.strip()
        # Clean any trailing think tags or artifacts
        out = out.split("<|im_end|>")[0].strip()
        if "[Start thinking]" in out:
            # strip think if present
            if "[End thinking]" in out:
                out = out.split("[End thinking]")[-1].strip()
        return out, elapsed
    except Exception as e:
        return f"Error: {e}", time.time() - t0

print("="*75)
print("TESTING TRANSCRIPT AUTO-CORRECTION ON LAPTOP")
print("="*75)

for s in test_samples:
    print(f"\n--- {s['id']} ---")
    print(f"RAW INPUT   : {s['raw']}")
    
    # 1. Tier 1 Test
    t0 = time.perf_counter()
    clean_t1 = fast_phonetic_clean(s['raw'], "")
    t1_ms = (time.perf_counter() - t0) * 1000
    print(f"TIER 1 (Lex): {clean_t1}  [Time: {t1_ms:.3f} ms]")
    
    # 2. Tier 2 Test (Qwen 0.6B LLM)
    out_llm, llm_sec = run_qwen_correction(s['raw'], s['context'])
    print(f"TIER 2 (LLM): {out_llm}  [Time: {llm_sec:.2f} s]")

print("\n" + "="*75)
print("BENCHMARK COMPLETED")
