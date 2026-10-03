import gc
import os
import sys
import time
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

SWIFT_DIR = Path(r"D:\nishant\toolchain\convert\out\hinglish-swift")
QWEN_DIR = Path(r"D:\nishant\toolchain\stt-eval\qwen3-hinglish")
BASE_DIR = Path(r"D:\nishant\toolchain\stt-eval\sherpa-onnx-whisper-base")
TINY_DIR = Path(r"D:\nishant\llm_research\llama_bin\stt\sherpa-onnx-whisper-tiny.en")
SAMPLES_DIR = Path("tools/hf_samples")

def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0

def main():
    selected_indices = [1, 6, 28, 52, 91]
    
    ground_truth = {
        1: "दोस्तों bash में nested और multilevel if statement के spoken tutorial में आपका स्वागत है",
        6: "इस tutorial के अनुसरण के लिए आपको लिनक्स operating system से परिचित होना चाहिए",
        28: "myname variable है जो यूज़र के द्वारा प्रविष्ट किया गया text जोकि यूज़र input है को संचित करता है",
        52: "type करें: chmod space plus x space nestedifelse sh",
        91: "mystring एक variable है जो निष्पादन के दौरान यूज़र द्वारा input शब्द संचित करता है",
    }
    
    # Load audio clips
    audio_clips = {}
    for idx in selected_indices:
        wav_path = SAMPLES_DIR / f"sample_{idx}.wav"
        samples = read_wav(wav_path)
        audio_clips[idx] = {
            "path": wav_path,
            "samples": samples,
            "dur": len(samples) / 16000.0,
            "gt": ground_truth[idx],
        }
        
    print(f"Loaded {len(audio_clips)} test audio clips.", flush=True)

    configs = [
        ("Whisper Swift (lang=en / Roman)", lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
            decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
            tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
            language="en", task="transcribe", num_threads=4,
        )),
        ("Whisper Swift (lang=hi / Devanagari)", lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(SWIFT_DIR / "hinglish-swift-encoder.int8.onnx"),
            decoder=str(SWIFT_DIR / "hinglish-swift-decoder.int8.onnx"),
            tokens=str(SWIFT_DIR / "hinglish-swift-tokens.txt"),
            language="hi", task="transcribe", num_threads=4,
        )),
        ("Whisper Base (lang=hi)", lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(BASE_DIR / "base-encoder.int8.onnx"),
            decoder=str(BASE_DIR / "base-decoder.int8.onnx"),
            tokens=str(BASE_DIR / "base-tokens.txt"),
            language="hi", task="transcribe", num_threads=4,
        )),
        ("Whisper Tiny.en (baseline)", lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(TINY_DIR / "tiny.en-encoder.int8.onnx"),
            decoder=str(TINY_DIR / "tiny.en-decoder.int8.onnx"),
            tokens=str(TINY_DIR / "tiny.en-tokens.txt"),
            language="en", task="transcribe", num_threads=4,
        )),
        ("Qwen3-ASR Hinglish (int8)", lambda: sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=str(QWEN_DIR / "conv_frontend.onnx"),
            encoder=str(QWEN_DIR / "encoder.int8.onnx"),
            decoder=str(QWEN_DIR / "decoder.int8.onnx"),
            tokenizer=str(QWEN_DIR / "tokenizer"),
            num_threads=4, max_new_tokens=256, max_total_len=1024,
        )),
    ]

    all_results = {idx: {} for idx in selected_indices}

    for model_name, loader in configs:
        print(f"\n=======================================================", flush=True)
        print(f"Loading Model: {model_name}...", flush=True)
        t_load = time.time()
        try:
            rec = loader()
            print(f"Model loaded in {time.time() - t_load:.2f}s", flush=True)
        except Exception as e:
            print(f"FAILED to load {model_name}: {e}", flush=True)
            continue
            
        for idx in selected_indices:
            clip = audio_clips[idx]
            t0 = time.time()
            st = rec.create_stream()
            st.accept_waveform(16000, clip["samples"])
            rec.decode_stream(st)
            res_text = st.result.text.strip()
            elapsed = time.time() - t0
            rtf = elapsed / clip["dur"]
            
            print(f"  [Sample {idx} ({clip['dur']:.1f}s)] RTF: {rtf:.2f} ({elapsed:.2f}s) -> {res_text}", flush=True)
            all_results[idx][model_name] = {
                "text": res_text,
                "elapsed": elapsed,
                "rtf": rtf
            }
            
        del rec
        gc.collect()

    print("\n\n" + "="*80, flush=True)
    print("DETAILED COMPARISON SUMMARY TABLE", flush=True)
    print("="*80, flush=True)
    for idx in selected_indices:
        clip = audio_clips[idx]
        print(f"\nSample #{idx} ({clip['dur']:.1f}s):", flush=True)
        print(f"  GROUND TRUTH: {clip['gt']}", flush=True)
        for m_name, res in all_results[idx].items():
            print(f"  - {m_name:35s} (RTF {res['rtf']:.2f}): {res['text']}", flush=True)

if __name__ == "__main__":
    main()
