import os
import sys
import subprocess
import sqlite3
import wave
from pathlib import Path
import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

adb = r"C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZA222ZJMQG"

db_path = Path("tools/nishu_device.db")
with open(db_path, "wb") as f:
    subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", "databases/nishu.db"], stdout=f, check=True)

con = sqlite3.connect(db_path)
cur = con.cursor()

rows = cur.execute("SELECT id, title, createdAt, status, durationMs, audioPath FROM conversation ORDER BY id DESC LIMIT 8").fetchall()
print("=== RECENT CONVERSATIONS IN APP DATABASE ===")
for r in rows:
    cid, title, createdAt, status, dur, audioPath = r
    print(f"ID: {cid:2d} | Title: {title:<35} | Status: {status:<10} | Dur: {dur/1000.0:5.1f}s | Path: {audioPath}")

print("\n=== LATEST CONVERSATIONS TRANSCRIPTS ===")
for r in rows[:4]:
    cid = r[0]
    tlines = cur.execute("SELECT startMs, endMs, text, speakerLabel FROM transcript_segment WHERE conversationId = ? ORDER BY startMs", (cid,)).fetchall()
    print(f"\n--- [ID {cid}] {r[1]} ({len(tlines)} lines) ---")
    for tl in tlines:
        print(f"  [{tl[0]/1000.0:4.1f}s - {tl[1]/1000.0:4.1f}s] ({tl[3]}): {tl[2]}")

con.close()
