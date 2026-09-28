#!/usr/bin/env python3
"""
ScummLearn: extract every text line of a game, with its line ID, without running it.

SCUMM v7/v8 games (The Curse of Monkey Island, Full Throttle, The Dig) keep each line
in the script resources as "/ID.NNN/English text\\0", e.g. "/CANNON.065/locked door".
The ID names the script it belongs to (CANNON = the gun deck room) and is stable, so
translations are stored by ID.

Usage:
    python3 extract_lines.py <game folder> -o lines.json

Output: a JSON list of {"id", "group", "text"} in game-file order, one entry per ID.
Lines that only appear inside videos (SMUSH .SAN files are compressed) are not found
here; the app logs them when they play (kind "video"), and translate_video_lines can
pick them up from learn.jsonl later.
"""
import argparse
import json
import os
import re
import sys

# IDs: "CANNON.065" (demo) or "WGSO001" (full game); always contain a digit.
LINE_RE = re.compile(rb'/([A-Za-z][A-Za-z0-9_]{2,19}(?:\.[0-9]{1,4})?)/([^\x00]{1,400}?)\x00')
# Real text is printable Latin-1; the engine uses a few control codes (^, \xff) we drop.
CTRL_RE = re.compile(r'\xff.|[\x00-\x1f]')


def clean(raw: bytes) -> str:
    s = raw.decode('latin-1')
    s = CTRL_RE.sub('', s)
    return re.sub(r'\s+', ' ', s).strip()


def looks_like_text(s: str) -> bool:
    if not s:
        return False
    printable = sum(1 for c in s if ' ' <= c <= '~' or c in 'áéíóúñü¡¿')
    # binary junk that happens to follow a "/XYZ12/" pattern is mostly symbols
    wordy = sum(1 for c in s if c.isalnum() or c in " '.,!?-<>`\"%()/:;=*&")
    return printable / len(s) > 0.95 and wordy / len(s) > 0.9 and any(c.isalpha() for c in s)


def extract(folder: str):
    seen = {}
    order = []
    for root, _dirs, files in os.walk(folder):
        for name in sorted(files):
            path = os.path.join(root, name)
            if os.path.getsize(path) > 600 * 1024 * 1024:
                continue
            # videos, audio, fonts and documents never hold script text (and their binary
            # data can look like "/ID/text" by chance)
            if name.upper().rsplit('.', 1)[-1] in ('SAN', 'BUN', 'NUT', 'IMX', 'PDF', 'EXE', 'ICO', 'DLL', 'LNK'):
                continue
            with open(path, 'rb') as f:
                data = f.read()
            for m in LINE_RE.finditer(data):
                line_id = m.group(1).decode('ascii').upper()
                if not any(c.isdigit() for c in line_id):
                    continue
                text = clean(m.group(2))
                if not looks_like_text(text) or line_id in seen:
                    continue
                seen[line_id] = text
                order.append({'id': line_id, 'group': re.sub(r'[0-9.]+$', '', line_id) or line_id, 'text': text, 'file': name})
    return order


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('folder')
    ap.add_argument('-o', '--out', default='lines.json')
    args = ap.parse_args()
    lines = extract(args.folder)
    if not lines:
        sys.exit('No lines found. Is this a SCUMM v7/v8 game folder?')
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(lines, f, ensure_ascii=False, indent=1)
    groups = {}
    for l in lines:
        groups[l['group']] = groups.get(l['group'], 0) + 1
    print(f'{len(lines)} lines in {len(groups)} groups -> {args.out}')
    for g, n in sorted(groups.items(), key=lambda x: -x[1])[:15]:
        print(f'  {g:12} {n}')


if __name__ == '__main__':
    main()
