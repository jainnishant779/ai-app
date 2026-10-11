import os
import subprocess
import sys
from pathlib import Path

PKG = "com.nishu.app"
MODELS_DIR = Path(r"d:\nishu-android\tools\models")


def adb_cmd(*args):
    return subprocess.run(["adb"] + list(args), capture_output=True, text=True, check=True)


def push_file(local_path: Path, remote_subpath: str):
    if not local_path.exists():
        print(f"SKIP (missing): {local_path}")
        return
    name = local_path.name
    remote_full = f"files/models/{remote_subpath}"
    remote_dir = "/".join(remote_full.split("/")[:-1])
    local_size = local_path.stat().st_size

    # Check remote size
    check = subprocess.run(["adb", "shell", f"run-as {PKG} stat -c %s {remote_full}"], capture_output=True, text=True)
    if check.stdout.strip() == str(local_size):
        print(f"PRESENT: {remote_subpath} ({local_size} bytes)")
        return

    print(f"Pushing {name} ({local_size / 1024 / 1024:.1f} MB) -> {remote_subpath} ...")
    subprocess.run(["adb", "push", str(local_path), f"/data/local/tmp/{name}"], check=True)
    subprocess.run(["adb", "shell", f"run-as {PKG} mkdir -p {remote_dir}"], check=True)
    subprocess.run(["adb", "shell", f"run-as {PKG} cp /data/local/tmp/{name} {remote_full}"], check=True)
    subprocess.run(["adb", "shell", f"rm /data/local/tmp/{name}"], check=True)

    check = subprocess.run(["adb", "shell", f"run-as {PKG} stat -c %s {remote_full}"], capture_output=True, text=True)
    if check.stdout.strip() == str(local_size):
        print(f"VERIFIED: {remote_subpath}")
    else:
        print(f"FAIL: {remote_subpath} (device has {check.stdout.strip()} bytes, expected {local_size})")


def main():
    # 1. LLM
    push_file(MODELS_DIR / "model.gguf", "llm/model.gguf")

    # 2. VAD
    push_file(MODELS_DIR / "silero_vad.onnx", "stt/silero_vad.onnx")

    # 3. Qwen3-Hinglish
    qwen_dir = MODELS_DIR / "qwen3-hinglish"
    if qwen_dir.exists():
        for f in qwen_dir.glob("*"):
            if f.is_file():
                push_file(f, f"stt/qwen3-hinglish/{f.name}")
        tok_dir = qwen_dir / "tokenizer"
        if tok_dir.exists():
            for f in tok_dir.glob("*"):
                if f.is_file():
                    push_file(f, f"stt/qwen3-hinglish/tokenizer/{f.name}")

    # 4. Whisper-Small
    small_dir = MODELS_DIR / "whisper-small"
    if small_dir.exists():
        for f in small_dir.glob("*"):
            if f.is_file():
                push_file(f, f"stt/whisper-small/{f.name}")

    print("All models pushed successfully!")


if __name__ == "__main__":
    main()
