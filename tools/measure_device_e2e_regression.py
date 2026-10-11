"""
Physical Device End-to-End Regression Test Runner for Nishu AI (Motorola Edge 60 Fusion).
Monitors thermals, PSS/heap, processing latency, VAD chunk boundaries, cross-talk labels,
and Room DB persistence.
"""
import subprocess
import time
import json
import sqlite3
import os

DEVICE = "ZA222ZJMQG"

def adb(cmd):
    full_cmd = f"adb -s {DEVICE} {cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip()

def get_thermals():
    out = adb("shell dumpsys thermalservice")
    temps = {}
    for line in out.splitlines():
        if "Temperature{" in line:
            # Temperature{mValue=63.149, mType=0, mName=CPU, mStatus=0}
            parts = line.split(",")
            val = None
            name = None
            for p in parts:
                if "mValue=" in p:
                    val = float(p.split("=")[1])
                elif "mName=" in p:
                    name = p.split("=")[1].strip(" }")
            if name and val is not None:
                temps[name] = val
    return temps

def get_meminfo():
    out = adb("shell dumpsys meminfo com.nishu.app")
    mem = {}
    for line in out.splitlines():
        if "Java Heap:" in line:
            parts = line.split()
            if len(parts) >= 3:
                mem["java_heap_kb"] = int(parts[2])
        elif "Native Heap:" in line and "TOTAL" not in line:
            parts = line.split()
            if len(parts) >= 3:
                mem["native_heap_kb"] = int(parts[2])
        elif "TOTAL PSS:" in line:
            parts = line.split()
            for i, p in enumerate(parts):
                if p == "PSS:":
                    mem["total_pss_kb"] = int(parts[i+1])
                elif p == "RSS:":
                    mem["total_rss_kb"] = int(parts[i+1])
    return mem

def pull_database():
    os.makedirs("tools", exist_ok=True)
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db > tools\\nishu.db", shell=True)
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-wal > tools\\nishu.db-wal", shell=True)
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-shm > tools\\nishu.db-shm", shell=True)

def inspect_conversation(conv_id):
    pull_database()
    con = sqlite3.connect("tools/nishu.db")
    cur = con.cursor()
    conv = cur.execute("SELECT id, title, durationMs, status, statusDetail, sttModelId, createdAt FROM conversation WHERE id = ?", (conv_id,)).fetchone()
    segments = cur.execute("SELECT id, startMs, endMs, speakerLabel, text FROM transcript_segment WHERE conversationId = ? ORDER BY startMs ASC", (conv_id,)).fetchall()
    summary = cur.execute("SELECT conversationId, bulletsText, chunkCount, failedChunks, llmModelId FROM summary WHERE conversationId = ?", (conv_id,)).fetchone()
    tasks = cur.execute("SELECT id, text, done FROM task WHERE conversationId = ?", (conv_id,)).fetchall()
    decisions = cur.execute("SELECT id, text FROM decision WHERE conversationId = ?", (conv_id,)).fetchall()
    return {
        "conversation": conv,
        "segments": segments,
        "summary": summary,
        "tasks": tasks,
        "decisions": decisions
    }

if __name__ == "__main__":
    print("Baseline Thermals:", get_thermals())
    print("Baseline Meminfo:", get_meminfo())
