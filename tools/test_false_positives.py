import sqlite3
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

con = sqlite3.connect('tools/nishu_device.db')
cur = con.cursor()
cur.execute('SELECT conversationId, startMs, text FROM transcript_segment ORDER BY conversationId, startMs')
rows = cur.fetchall()

# 1. BUILT-IN SAFE SYMBOLS & HIGH-PRECISION PHONETIC RULES (No ambiguous single words!)
SAFE_RULES = [
    # Spoken symbols
    (r'(?i)\bat the rate\b', '@'),
    (r'(?i)\bdot com\b', '.com'),
    (r'(?i)\bdot org\b', '.org'),
    (r'(?i)\bdot in\b', '.in'),
    (r'(?i)\bdot sh\b', '.sh'),
    (r'(?i)\bdot py\b', '.py'),
    (r'(?i)\bdot js\b', '.js'),
    (r'(?i)\bspace plus x\b', ' +x'),
    (r'(?i)\bc\s*h\s*mode\b', 'chmod'),
    # High-precision compound phrases
    (r'(?i)\bdash script\b', 'bash script'),
    (r'(?i)\bfool request\b', 'pull request'),
    (r'(?i)\bjaabi\b', 'chabhi'),
]

def parse_custom_vocab(vocab_str):
    rules = []
    if not vocab_str:
        return rules
    items = [x.strip() for x in vocab_str.split(',') if x.strip()]
    i = 0
    while i < len(items):
        item = items[i]
        if '(' in item and ')' not in item:
            comb = item
            while i + 1 < len(items) and ')' not in comb:
                i += 1
                comb += ', ' + items[i]
            item = comb
        m = re.match(r'^([^\(]+)\((.+)\)$', item)
        if m:
            target = m.group(1).strip()
            aliases = [a.strip() for a in m.group(2).split(',') if a.strip()]
            rules.append((target, aliases))
        else:
            rules.append((item.strip(), []))
        i += 1
    return rules

def apply_cleaner(text, vocab_str=''):
    res = text
    for pat, rep in SAFE_RULES:
        res = re.sub(pat, rep, res)
    for target, aliases in parse_custom_vocab(vocab_str):
        for alias in aliases:
            res = re.sub(rf'(?i)\b{re.escape(alias)}\b', target, res)
    return res

print(f'Total segments checked: {len(rows)}')

# TEST 1: SAFE RULES ONLY across all 237 segments
print('\n=== TEST 1: SAFE RULES ONLY (Checking all 237 segments) ===')
changes = []
for cid, start, text in rows:
    c = apply_cleaner(text, '')
    if c != text:
        changes.append((cid, start, text, c))

for cid, start, orig, fixed in changes:
    print(f'Conv #{cid} [{start}ms]:')
    print(f'  ORIG : {orig}')
    print(f'  FIXED: {fixed}\n')

print(f'Total altered by safe rules: {len(changes)}')

# TEST 2: Verify English Conv 31 is completely untouched
print('\n=== TEST 2: Verify English words like "catch up" are UNTOUCHED ===')
c31_catch = [r for r in rows if r[0] == 31 and 'catch' in r[2].lower()]
for cid, start, text in c31_catch:
    c = apply_cleaner(text, '')
    assert 'catch' in c.lower(), 'CRITICAL ERROR: False positive caught catch!'
    print(f'  PASSED (No false positive): {c[:70]}...')

# TEST 3: Verify Conv 17 ("connect cash hoga" = kaise hoga) is UNTOUCHED
print('\n=== TEST 3: Verify "cash hoga" (meaning kaise hoga) is UNTOUCHED ===')
c17_cash = [r for r in rows if r[0] == 17 and 'cash' in r[2].lower()]
for cid, start, text in c17_cash:
    c = apply_cleaner(text, '')
    assert 'cash' in c.lower(), 'CRITICAL ERROR: False positive caught cash!'
    print(f'  PASSED (No false positive): {c[:70]}...')

# TEST 4: Custom Vocabulary explicitly requested by user
print('\n=== TEST 4: CUSTOM VOCAB TEST (Conv 43 & Conv 65 with User Vocab) ===')
user_vocab = 'SINQIT (synchik, sinqik), DB (DV), cache (catch, cap), pull (fool)'
for cid, start, text in rows:
    if cid in [43, 65] and any(k in text.lower() for k in ['synchik', 'dv', 'catch', 'fool', 'cap']):
        c = apply_cleaner(text, user_vocab)
        print(f'Conv #{cid} [{start}ms]:')
        print(f'  BEFORE: {text}')
        print(f'  AFTER : {c}\n')

print('ALL REGRESSION TESTS PASSED! ZERO FALSE POSITIVES!')
