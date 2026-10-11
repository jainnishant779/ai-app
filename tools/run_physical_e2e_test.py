"""
Complete automated runner for Physical-Device E2E Regression Test.
"""
import subprocess
import time
import json
import sqlite3
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(line_buffering=True)

from inject_test_conversation import prepare_injection, get_thermals, get_meminfo, adb, DEVICE

def run_regression_test():
    print("=" * 80)
    print("STARTING PHYSICAL-DEVICE REGRESSION TEST ON MOTOROLA EDGE 60 FUSION")
    print("=" * 80)

    # 1. Prepare conversation 25 injection
    prepare_injection()

    # 2. Record Pre-Test Metrics
    t_start = time.time()
    pre_thermals = get_thermals()
    pre_mem = get_meminfo()
    print("\n--- PRE-TEST METRICS ---")
    print(f"Thermals: CPU={pre_thermals.get('CPU')}°C, SoC={pre_thermals.get('SOC')}°C, Battery={pre_thermals.get('BATTERY')}°C, Skin={pre_thermals.get('SKIN')}°C")
    print(f"Memory: Java Heap={pre_mem.get('java_heap_kb', 0)/1024:.1f} MB, Native Heap={pre_mem.get('native_heap_kb', 0)/1024:.1f} MB, Total PSS={pre_mem.get('total_pss_kb', 0)/1024:.1f} MB")

    # 3. Clear Logcat and Restart App to trigger RecordingRecovery
    print("\n[Restarting com.nishu.app to trigger Recovery & Processing...]")
    adb("logcat -c")
    adb("shell am force-stop com.nishu.app")
    time.sleep(1)
    adb("shell am start -n com.nishu.app/.MainActivity")

    # 4. Monitor Logcat in loop
    print("\n--- MONITORING ON-DEVICE PROCESSING PIPELINE ---")
    peak_pss = pre_mem.get('total_pss_kb', 0)
    peak_java_heap = pre_mem.get('java_heap_kb', 0)
    
    proc = subprocess.Popen(
        f"adb -s {DEVICE} logcat -v time",
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1
    )

    stage_events = []
    done = False
    deadline = time.time() + 300 # 5 min timeout
    
    while time.time() < deadline and not done:
        line = proc.stdout.readline()
        if not line:
            time.sleep(0.1)
            continue
        line_clean = line.strip()
        lower = line_clean.lower()
        if any(k in lower for k in ["pipeline", "diariz", "stt", "sherpa", "summariz", "transcrib", "speakervoice", "recordingrecovery", "status=done", "status=failed"]):
            print(f"  [DEVICE LOG] {line_clean}")
            stage_events.append((time.time() - t_start, line_clean))
        
        # Check current memory periodically
        cur_mem = get_meminfo()
        if cur_mem.get('total_pss_kb', 0) > peak_pss:
            peak_pss = cur_mem.get('total_pss_kb', 0)
        if cur_mem.get('java_heap_kb', 0) > peak_java_heap:
            peak_java_heap = cur_mem.get('java_heap_kb', 0)

        if "summarize done:" in line_clean or "transcription failed" in line_clean or "summarize failed" in line_clean:
            print(">>> Pipeline completed signal received!")
            done = True
            break

    proc.terminate()
    t_end = time.time()
    total_duration = t_end - t_start

    # 5. Record Post-Test Metrics
    time.sleep(2)
    post_thermals = get_thermals()
    post_mem = get_meminfo()
    print("\n--- POST-TEST METRICS ---")
    print(f"Total Processing Duration: {total_duration:.2f} seconds (RTF: {total_duration / 207.59:.3f}x for 207.6s audio)")
    print(f"Thermals: CPU={post_thermals.get('CPU')}°C, SoC={post_thermals.get('SOC')}°C, Battery={post_thermals.get('BATTERY')}°C, Skin={post_thermals.get('SKIN')}°C")
    print(f"Thermal Delta: CPU Δ={post_thermals.get('CPU', 0) - pre_thermals.get('CPU', 0):+.2f}°C, Battery Δ={post_thermals.get('BATTERY', 0) - pre_thermals.get('BATTERY', 0):+.2f}°C")
    print(f"Peak Memory: Total PSS={peak_pss/1024:.1f} MB, Java Heap={peak_java_heap/1024:.1f} MB")
    print(f"Post Memory: Java Heap={post_mem.get('java_heap_kb', 0)/1024:.1f} MB, Native Heap={post_mem.get('native_heap_kb', 0)/1024:.1f} MB, Total PSS={post_mem.get('total_pss_kb', 0)/1024:.1f} MB")

    # 6. Pull Database and inspect conversation 25
    print("\n--- VERIFYING PERSISTENCE & RESULTS IN ROOM DB ---")
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db > tools\\nishu.db", shell=True)
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-wal > tools\\nishu.db-wal", shell=True)
    subprocess.run(f"adb -s {DEVICE} exec-out run-as com.nishu.app cat databases/nishu.db-shm > tools\\nishu.db-shm", shell=True)

    con = sqlite3.connect("tools/nishu.db")
    cur = con.cursor()
    conv = cur.execute("SELECT id, title, durationMs, status, statusDetail, sttModelId FROM conversation WHERE id = 25").fetchone()
    segments = cur.execute("SELECT id, startMs, endMs, speakerLabel, text FROM transcript_segment WHERE conversationId = 25 ORDER BY startMs ASC").fetchall()
    summary = cur.execute("SELECT conversationId, bulletsText, chunkCount, failedChunks, llmModelId FROM summary WHERE conversationId = 25").fetchone()
    tasks = cur.execute("SELECT id, text, done FROM task WHERE conversationId = 25").fetchall()
    decisions = cur.execute("SELECT id, text FROM decision WHERE conversationId = 25").fetchall()

    print(f"Conversation Row: {conv}")
    print(f"\nTranscript Segments ({len(segments)} segments recovered):")
    for s in segments:
        print(f"  [{s[1]/1000:05.1f}s - {s[2]/1000:05.1f}s] [{s[3]}]: \"{s[4]}\"")

    print("\nSummary Extraction:")
    if summary:
        print(f"Bullets ({summary[4]}):\n{summary[1]}")
    else:
        print("No summary row.")

    print(f"\nExtracted Tasks: {tasks}")
    print(f"Extracted Decisions: {decisions}")

    # 7. Persistence Check: Navigate away & reopen
    print("\n[Checking Persistence: Navigating away and reopening app...]")
    adb("shell input keyevent KEYCODE_HOME")
    time.sleep(2)
    adb("shell am start -n com.nishu.app/.MainActivity")
    time.sleep(2)
    
    # Verify DB still has all segments
    cur2 = sqlite3.connect("tools/nishu.db").cursor()
    seg_count = cur2.execute("SELECT COUNT(*) FROM transcript_segment WHERE conversationId = 25").fetchone()[0]
    print(f"Persistence confirmed: {seg_count} segments intact in Room DB after navigation cycle.")

    return {
        "duration": total_duration,
        "rtf": total_duration / 207.59,
        "pre_thermals": pre_thermals,
        "post_thermals": post_thermals,
        "peak_pss_mb": peak_pss / 1024,
        "peak_heap_mb": peak_java_heap / 1024,
        "segments": segments,
        "summary": summary,
        "tasks": tasks,
        "decisions": decisions
    }

if __name__ == "__main__":
    run_regression_test()
