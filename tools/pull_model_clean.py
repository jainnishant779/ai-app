import subprocess
import os
import shutil
from pathlib import Path

adb = shutil.which("adb") or r"C:\Users\nishant\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZA222ZJMQG"
remote_dir = "files/models/stt/qwen3-hinglish"
local_dir = Path("tools/models/qwen3-hinglish")

local_dir.mkdir(parents=True, exist_ok=True)
(local_dir / "tokenizer").mkdir(parents=True, exist_ok=True)

files = [
    "tokenizer/vocab.json",
    "tokenizer/merges.txt",
    "tokenizer/tokenizer_config.json",
    "conv_frontend.onnx",
    "encoder.int8.onnx",
    "decoder.int8.onnx"
]

for f in files:
    out_path = local_dir / f
    remote_path = f"{remote_dir}/{f}"
    print(f"Pulling {f}...", flush=True)
    with open(out_path, "wb") as out_f:
        subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", remote_path], stdout=out_f, check=True)
    size_mb = os.path.getsize(out_path) / (1024 * 1024)
    print(f"Finished {f}: {size_mb:.1f} MB", flush=True)

print("ALL FILES PULLED SUCCESSFULLY!", flush=True)
