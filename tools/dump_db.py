import subprocess
import sqlite3

adb = r"C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZA222ZJMQG"

for fname in ["nishu.db", "nishu.db-wal", "nishu.db-shm"]:
    with open(f"tools/{fname}", "wb") as f:
        subprocess.run([adb, "-s", device, "exec-out", "run-as", "com.nishu.app", "cat", f"databases/{fname}"], stdout=f)

conn = sqlite3.connect("tools/nishu.db")
c = conn.cursor()
print("=== CONVERSATIONS ===")
for col in c.execute("PRAGMA table_info(summary)"):
    print(col)

print("\n=== SUMMARIES (>= 40) ===")
for row in c.execute("SELECT * FROM summary WHERE conversationId >= 40 ORDER BY conversationId DESC"):
    print(row)
    print()

print("\n=== RECENT SEGMENTS (Conversation >= 42) ===")
for row in c.execute("SELECT conversationId, speakerLabel, startMs, endMs, text FROM transcript_segment WHERE conversationId >= 42 ORDER BY conversationId DESC, startMs ASC"):
    print(f"Conv {row[0]} | Speaker {row[1]} | {row[2]/1000.0:.2f}s-{row[3]/1000.0:.2f}s: \"{row[4]}\"")
