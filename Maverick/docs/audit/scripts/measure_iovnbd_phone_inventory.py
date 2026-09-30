#!/usr/bin/env python3
import csv
import glob
import json
import os

ROOT = r'C:\Users\STUDENT\Desktop\Maverick\Maverick45\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset'
files = sorted(glob.glob(os.path.join(ROOT, '**', '*.csv'), recursive=True))
phone_files = []
for f in files:
    name = os.path.basename(f)
    if name.lower().startswith('s') and 'v-' not in name.lower():
        phone_files.append(f)

by_driver = {}
for f in phone_files:
    rel = os.path.relpath(f, ROOT)
    driver = rel.split(os.sep)[0]
    by_driver.setdefault(driver, {'files': 0, 'rows': 0, 'size': 0})
    by_driver[driver]['files'] += 1
    by_driver[driver]['size'] += os.path.getsize(f)
    with open(f, 'r', encoding='utf-8', errors='replace', newline='') as fh:
        reader = csv.reader(fh)
        next(reader, None)
        n = 0
        for _ in reader:
            n += 1
    by_driver[driver]['rows'] += n

summary = {
    'root': ROOT,
    'input_rule': 'phone S files only; V files are ground truth only',
    'files': len(phone_files),
    'rows': sum(v['rows'] for v in by_driver.values()),
    'size_mb': round(sum(v['size'] for v in by_driver.values()) / (1024 * 1024), 2),
    'by_driver': {k: {'files': v['files'], 'rows': v['rows'], 'size_mb': round(v['size'] / (1024 * 1024), 2)} for k, v in sorted(by_driver.items())},
}
print(json.dumps(summary, indent=2))
