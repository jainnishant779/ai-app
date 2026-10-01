"""Convert Oriserve/Whisper-Hindi2Hinglish-Swift (HF transformers) to sherpa-onnx whisper int8 files.

Pipeline: HF safetensors -> OpenAI-whisper .pt (mapping shipped with the model) -> sherpa-onnx's official
export-onnx.py (patched only to accept this model name) -> hinglish-swift-{encoder,decoder}[.int8].onnx + tokens.

Run with the conversion venv (torch CPU, openai-whisper, transformers, onnx, onnxruntime):
  D:\\nishant\\toolchain\\convert-venv\\Scripts\\python.exe tools\\convert_hinglish_whisper.py
Everything is written under D:\\nishant\\toolchain\\convert (C: is nearly full).
"""
import json
import re
import shutil
import subprocess
import sys
from collections import OrderedDict
from pathlib import Path

import torch
import whisper
from transformers import AutoModelForSpeechSeq2Seq
from whisper.model import ModelDimensions, Whisper

ROOT = Path(r"D:\nishant\toolchain\convert")
HF_DIR = ROOT / "swift"
NAME = "hinglish-swift"
PT = ROOT / f"{NAME}.pt"


def hf_to_openai_pt() -> None:
    mapping = OrderedDict(json.loads((HF_DIR / "convert_hf2openai.json").read_text()))
    model = AutoModelForSpeechSeq2Seq.from_pretrained(str(HF_DIR), low_cpu_mem_usage=True, use_safetensors=True)
    c = model.config
    dims = {
        "n_mels": c.num_mel_bins, "n_vocab": c.vocab_size,
        "n_audio_ctx": c.max_source_positions, "n_audio_state": c.d_model,
        "n_audio_head": c.encoder_attention_heads, "n_audio_layer": c.encoder_layers,
        "n_text_ctx": c.max_target_positions, "n_text_state": c.d_model,
        "n_text_head": c.decoder_attention_heads, "n_text_layer": c.decoder_layers,
    }
    print("dims:", dims)

    def translate(key: str):
        for pattern, repl in mapping.items():
            if re.match(pattern, key):
                return re.sub(pattern, repl, key)
        return None

    sd = {}
    for k, v in model.state_dict().items():
        nk = translate(k.replace("model.", "", 1))
        if nk is not None:
            sd[nk] = v

    # Load into the real OpenAI architecture to prove the mapping is complete, then save its own state dict.
    ref = Whisper(ModelDimensions(**dims))
    missing, unexpected = ref.load_state_dict(sd, strict=False)
    print("missing:", missing, "unexpected:", unexpected)
    # Sinusoidal encoder positions are a non-persistent buffer in current whisper; anything else is a real error.
    assert all(m == "encoder.positional_embedding" for m in missing), f"mapping incomplete: {missing}"
    torch.save({"dims": dims, "model_state_dict": ref.state_dict()}, PT)
    print("saved", PT, f"({PT.stat().st_size / 1e6:.0f} MB)")


def patched_exporter() -> Path:
    src = (ROOT / "export-onnx.py").read_text()
    src = src.replace('"medium-aishell",\n            ]', f'"medium-aishell", "{NAME}",\n            ]')
    src = src.replace(
        "    elif name == \"medium-aishell\":",
        f"    elif name == \"{NAME}\":\n        return whisper.load_model(\"{PT.as_posix()}\")\n    elif name == \"medium-aishell\":",
    )
    # Current PyTorch defaults to the dynamo exporter; sherpa's script (dynamic_axes, external caches) targets the
    # TorchScript one, which is still available with dynamo=False.
    src = src.replace("opset_version=opset_version,", "opset_version=opset_version,\n        dynamo=False,")
    out = ROOT / "export-onnx-patched.py"
    out.write_text(src)
    return out


def main() -> None:
    if not PT.exists():
        hf_to_openai_pt()
    exporter = patched_exporter()
    subprocess.run([sys.executable, str(exporter), "--model", NAME], cwd=ROOT, check=True)
    out = ROOT / "out" / NAME
    out.mkdir(parents=True, exist_ok=True)
    for f in ROOT.glob(f"{NAME}-*"):
        shutil.copy2(f, out / f.name)
    for f in sorted(out.iterdir()):
        print(f"  {f.name:40s} {f.stat().st_size / 1e6:7.1f} MB")


if __name__ == "__main__":
    main()
