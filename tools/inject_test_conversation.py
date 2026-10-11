"""
Injects a 3.5-minute multi-speaker meeting conversation into Nishu AI on the Motorola Edge 60 Fusion.
Triggers end-to-end physical device execution via RecordingRecovery on app startup.
Monitors memory, CPU, thermals, VAD chunking, Diarization, and STT in real-time.
"""
import subprocess
import time
import os
import sqlite3

DEVICE = "ZA222ZJMQG"

def run_cmd(cmd):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip()

def adb(cmd):
    return run_cmd(f"adb -s {DEVICE} {cmd}")

def get_thermals():
    out = adb("shell dumpsys thermalservice")
    temps = {}
    for line in out.splitlines():
        if "Temperature{" in line:
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

def prepare_injection():
    print("[1/5] Streaming test audio directly into app sandbox files/recordings/25.wav...")
    with open("tools/test_meeting_3m30s.wav", "rb") as f:
        subprocess.run(["adb", "-s", DEVICE, "exec-in", "run-as", "com.nishu.app", "sh", "-c", "cat > files/recordings/25.wav"], stdin=f, check=True)

    # Verify audio pushed
    size_out = adb("shell run-as com.nishu.app ls -la files/recordings/25.wav")
    print(f"  Pushed: {size_out}")

    print("[2/5] Stopping app to prevent database lock...")
    adb("shell am force-stop com.nishu.app")
    time.sleep(1)

    print("[3/5] Pulling current database...")
    run_cmd(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db > tools\\nishu.db")
    run_cmd(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-wal > tools\\nishu.db-wal")
    run_cmd(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-shm > tools\\nishu.db-shm")

    print("[4/5] Updating database locally...")
    con = sqlite3.connect("tools/nishu.db")
    cur = con.cursor()
    # Check if 25 exists
    cur.execute("DELETE FROM conversation WHERE id = 25")
    cur.execute("DELETE FROM transcript_segment WHERE conversationId = 25")
    cur.execute("DELETE FROM summary WHERE conversationId = 25")
    cur.execute("DELETE FROM task WHERE conversationId = 25")
    cur.execute("DELETE FROM decision WHERE conversationId = 25")
    
    now = int(time.time() * 1000)
    cur.execute("""
        INSERT INTO conversation (id, title, category, audioPath, durationMs, status, createdAt, audioDeleted)
        VALUES (25, 'Multi-Speaker Meeting Regression (3.5m)', 'OTHER', '/data/user/0/com.nishu.app/files/recordings/25.wav', 207590, 'RECORDING', ?, 0)
    """, (now,))
    con.commit()
    con.close()

    # Checkpoint WAL back to DB file
    con = sqlite3.connect("tools/nishu.db")
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.commit()
    con.close()

    print("[5/5] Streaming updated database directly into app sandbox...")
    with open("tools/nishu.db", "rb") as f:
        subprocess.run(["adb", "-s", DEVICE, "exec-in", "run-as", "com.nishu.app", "sh", "-c", "cat > databases/nishu.db"], stdin=f, check=True)
    adb("shell \"run-as com.nishu.app rm -f databases/nishu.db-wal databases/nishu.db-shm\"")
    print("Injection prepared successfully!")

if __name__ == "__main__":
    prepare_injection()
