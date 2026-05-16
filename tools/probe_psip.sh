#!/bin/bash
set -e
URL="$1"
OUT=/tmp/cap.ts
rm -f "$OUT"
curl -s -m 5 -o "$OUT" "$URL"
ls -la "$OUT"
python3 <<'PY'
import sys
data = open('/tmp/cap.ts','rb').read()
pids = {}
for i in range(0, len(data)-188, 188):
    if data[i] != 0x47: continue
    p = ((data[i+1] & 0x1f) << 8) | data[i+2]
    pids[p] = pids.get(p, 0) + 1
print("packets:", len(data)//188)
print("unique PIDs:", len(pids))
for p in sorted(pids):
    flag = ''
    if p == 0x0000: flag = ' (PAT)'
    elif p == 0x0001: flag = ' (CAT)'
    elif p == 0x1FFB: flag = ' (PSIP)'
    elif p == 0x1FFF: flag = ' (NULL)'
    print(f"  PID 0x{p:04x} ({p:5d}): {pids[p]:5d}{flag}")
print()
print("HAS PSIP (0x1FFB):", 0x1FFB in pids, "(", pids.get(0x1FFB,0), "packets)")
PY
