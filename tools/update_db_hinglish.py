import sqlite3
import shutil
import subprocess
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

adb = shutil.which('adb') or r'C:\Users\nishant\AppData\Local\Android\Sdk\platform-tools\adb.exe'
device = 'ZA222ZJMQG'

common_words = {
    'तो': 'to', 'उससे': 'usse', 'कोई': 'koi', 'दिक्कत': 'dikkat', 'होगी': 'hogi',
    'तू': 'tu', 'गैंड': 'gaind', 'ओएसआर': 'OSR', 'कितना': 'kitna', 'पीकलोड': 'peakload',
    'ले': 'le', 'रही': 'rahi', 'है': 'hai', 'ये': 'ye', 'तुम': 'tum', 'चेक': 'check',
    'कर': 'kar', 'लोगे': 'loge', 'अभी': 'abhi', 'ना': 'na', 'नहीं': 'nahin',
}

def devanagari_to_hinglish(text):
    if not text:
        return text
    tokens = re.findall(r'[\u0900-\u097F]+|[^\u0900-\u097F]+', text)
    res = []
    for token in tokens:
        if not re.search(r'[\u0900-\u097F]', token):
            res.append(token)
        else:
            res.append(common_words.get(token, token))
    return ''.join(res)

conn = sqlite3.connect('tools/nishu.db')
c = conn.cursor()

for row in c.execute('SELECT id, text FROM transcript_segment;').fetchall():
    if row[1]:
        new_t = devanagari_to_hinglish(row[1])
        if new_t != row[1]:
            print(f"Segment {row[0]}: '{row[1]}' -> '{new_t}'")
            c.execute('UPDATE transcript_segment SET text = ? WHERE id = ?', (new_t, row[0]))

conn.commit()
conn.close()

subprocess.run([adb, '-s', device, 'push', 'tools/nishu.db', '/data/local/tmp/nishu.db'])
subprocess.run([adb, '-s', device, 'shell', 'run-as com.nishu.app cp /data/local/tmp/nishu.db databases/nishu.db'])
subprocess.run([adb, '-s', device, 'shell', 'rm /data/local/tmp/nishu.db'])
print('Updated database successfully!')
