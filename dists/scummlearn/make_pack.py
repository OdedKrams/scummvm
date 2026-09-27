#!/usr/bin/env python3
"""
Build learn_pack.json for one game.

    python3 make_pack.py --game comi --lines lines.json --he he_ids.json \
        [--words words.json] [--hints hints.json] [--dict old learn_dict.json] -o learn_pack.json

--lines  from extract_lines.py ([{id, text}])
--he     translations by ID ({id: hebrew}), e.g. merged translator output
--words  {word: {"he":..., "note":...}}
--hints  {"cooldown_sec":..., "rooms": {...}} (same format as res/raw/learn_hints.json)
--dict   an older {"lines": {en: he}, "words": {...}} dictionary to merge in (IDs win)

Copy the result to the game folder as learn_pack.json (or to the save folder as
learn_<gameid>.json). The app loads it when the game starts.
"""
import argparse, json
ap = argparse.ArgumentParser()
ap.add_argument('--game', required=True)
ap.add_argument('--lines'); ap.add_argument('--he'); ap.add_argument('--words')
ap.add_argument('--hints'); ap.add_argument('--dict'); ap.add_argument('-o', '--out', default='learn_pack.json')
a = ap.parse_args()
load = lambda p: json.load(open(p, encoding='utf-8')) if p else None

pack = {'format': 1, 'game': a.game, 'lines': {}, 'ids': {}, 'words': {}}
old = load(a.dict)
if old:
    pack['lines'].update(old.get('lines', {}))
    pack['words'].update(old.get('words', {}))
he = load(a.he) or {}
text_of = {}
for l in load(a.lines) or []:
    text_of[l['id']] = l['text']
missing = 0
for line_id, text in text_of.items():
    t = he.get(line_id)
    if t:
        pack['ids'][line_id] = t
        pack['lines'][text] = t     # games also show lines without their ID
    elif text not in pack['lines']:
        missing += 1
if a.words:
    pack['words'].update(load(a.words))
if a.hints:
    pack['hints'] = load(a.hints)
json.dump(pack, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print(f"{a.out}: {len(pack['lines'])} lines, {len(pack['ids'])} ids, {len(pack['words'])} words, "
      f"hints {'yes' if 'hints' in pack else 'no'}, {missing} lines without translation")
