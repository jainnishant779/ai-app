import subprocess
import sqlite3

DEVICE = 'ZA222ZJMQG'
def adb(cmd):
    return subprocess.run(f"adb -s {DEVICE} {cmd}", shell=True, capture_output=True, text=True).stdout.strip()

print("--- POST-TEST THERMALS ---")
t_out = adb("shell dumpsys thermalservice")
for l in t_out.splitlines():
    if any(k in l for k in ['CPU', 'SOC', 'BATTERY', 'SKIN']):
        if 'Temperature{' in l:
            print(l.strip())

print("\n--- POST-TEST MEMINFO ---")
m_out = adb("shell dumpsys meminfo com.nishu.app")
for l in m_out.splitlines():
    if any(k in l for k in ['Java Heap:', 'Native Heap:', 'TOTAL PSS:', 'TOTAL RSS:']):
        print(l.strip())

# Pull DB
subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db > tools\\nishu_final.db", shell=True)
subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-wal > tools\\nishu_final.db-wal", shell=True)
subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-shm > tools\\nishu_final.db-shm", shell=True)

con = sqlite3.connect("tools/nishu_final.db")
cur = con.cursor()
conv = cur.execute("SELECT id, title, durationMs, status, stage, sttModelId, llmModelId FROM conversation WHERE id = 25").fetchone()
print("\n--- CONVERSATION 25 ROW ---")
print(conv)

segments = cur.execute("SELECT startMs, endMs, speakerLabel, text FROM transcript_segment WHERE conversationId = 25 ORDER BY startMs ASC").fetchall()
print(f"\n--- TRANSCRIPT SEGMENTS ({len(segments)} segments recovered) ---")
for s in segments:
    print(f"  [{s[0]/1000:05.1f}s - {s[1]/1000:05.1f}s] [{s[2]}]: \"{s[3]}\"")

summary = cur.execute("SELECT bulletsText, chunkCount, llmModelId FROM summary WHERE conversationId = 25").fetchone()
print("\n--- GENERATED ON-DEVICE LLM SUMMARY ---")
if summary:
    print(f"Model: {summary[2]} | Bullets:\n{summary[0]}")

decisions = cur.execute("SELECT text FROM decision WHERE conversationId = 25").fetchall()
print("\n--- EXTRACTED DECISIONS ---")
print(decisions)
