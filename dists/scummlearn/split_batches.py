#!/usr/bin/env python3
"""Split lines.json into batch files for parallel translation (keeps script groups together)."""
import argparse, json, os
ap = argparse.ArgumentParser()
ap.add_argument('lines'); ap.add_argument('outdir'); ap.add_argument('--size', type=int, default=120)
ap.add_argument('--skip', help='JSON {id: hebrew} of lines already translated')
a = ap.parse_args()
lines = json.load(open(a.lines, encoding='utf-8'))
done = json.load(open(a.skip, encoding='utf-8')) if a.skip else {}
todo = [{'id': l['id'], 'group': l['group'], 'text': l['text']} for l in lines if l['id'] not in done]
os.makedirs(a.outdir, exist_ok=True)
batches, cur = [], []
for l in todo:
    # break at a group boundary once the batch is big enough, or hard at 1.5x size
    if cur and ((len(cur) >= a.size and l['group'] != cur[-1]['group']) or len(cur) >= a.size * 1.5):
        batches.append(cur); cur = []
    cur.append(l)
if cur: batches.append(cur)
for i, b in enumerate(batches, 1):
    json.dump(b, open(os.path.join(a.outdir, f'batch_{i:03d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(f'{len(todo)} lines -> {len(batches)} batches in {a.outdir}: ' + ', '.join(str(len(b)) for b in batches))
