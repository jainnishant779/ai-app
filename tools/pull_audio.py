import subprocess
import wave

import shutil

adb = shutil.which("adb") or r"C:\Users\nishant\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZA222ZJMQG"

for num in [198, 199]:
    out_path = f"tools/{num}.wav"
    with open(out_path, "wb") as f:
        subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", f"files/recordings/{num}.wav"], stdout=f)
    try:
        with wave.open(out_path, "rb") as w:
            print(f"tools/{num}.wav: SUCCESS, duration={w.getnframes()/16000.0:.2f}s, channels={w.getnchannels()}, rate={w.getframerate()}")
    except Exception as e:
        print(f"tools/{num}.wav: FAILED - {e}")
