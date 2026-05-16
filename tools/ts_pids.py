import sys
data = open(sys.argv[1],"rb").read()
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
    elif p == 0x1FFB: flag = ' (PSIP)'
    elif p == 0x1FFF: flag = ' (NULL)'
    print("  PID 0x{:04x} ({:5d}): {:5d}{}".format(p, p, pids[p], flag))
print("HAS_PSIP", 0x1FFB in pids, "count=", pids.get(0x1FFB,0))
