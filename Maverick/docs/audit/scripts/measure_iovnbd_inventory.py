#!/usr/bin/env python3
import csv, glob, os
root = r'C:\Users\STUDENT\Desktop\Maverick\Maverick45\IO-VNBD\Unsynchronised V and S Dataset\Categorised IOVNB (V) Dataset\V Dataset'
files = sorted(glob.glob(os.path.join(root, '**', '*.csv'), recursive=True))
counts = {}
rows = {}
size = {}
for f in files:
    driver = os.path.relpath(f, root).split(os.sep)[0]
    counts[driver] = counts.get(driver, 0) + 1
    with open(f, 'r', encoding='utf-8', errors='replace', newline='') as fh:
        reader = csv.reader(fh)
        header = next(reader, None)
        if header is None:
            continue
        n = 0
        for _ in reader:
            n += 1
    rows[driver] = rows.get(driver, 0) + n
    size[driver] = size.get(driver, 0) + os.path.getsize(f)
print('CSV count', len(files))
for driver in sorted(counts):
    print(f'{driver}\tfiles={counts[driver]}\trows={rows[driver]}\tsize_kb={size[driver]/1024:.1f}')
print('TOTAL rows', sum(rows.values()))
print('TOTAL size_mb', round(sum(size.values()) / (1024 * 1024), 2))
