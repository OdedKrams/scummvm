# ScummLearn learn packs: one file per game

The app itself is the same for every game. Everything specific to one game lives in a
**learn pack**, a single JSON file:

```json
{
  "format": 1,
  "game": "comi",
  "lines": {"I can't reach it.": "אני לא מגיע לזה."},
  "ids":   {"SYSTEM.009": "אני לא מגיע לזה."},
  "words": {"rope": {"he": "חבל", "note": ""}},
  "hints": {"cooldown_sec": 180, "rooms": {"3": [{"text": "...", "done_if": ["..."]}]}}
}
```

* `ids`: translation by line ID (`/CANNON.065/` in SCUMM v7/v8 games). This is the most reliable way to match a line.
* `lines`: translation by English text. It is the fallback for lines the engine shows without their ID.
* `words`: single words, used for one-word object names now and for quizzes later.
* `hints`: per room, a list of steps `{"he": general hint, "more": detailed hint (2 coins), "done_if": [markers]}`. The first step that isn't done is shown. Markers:
  * `has:a+b`: the hero carries a and b now, or carried them before.
  * `now:a`: carries a right now.
  * `obj:a` / `noobj:a`: a is in the room now / is gone from it.
  * Any other text: that line was heard.
  * Lines heard and items carried are stored with each save slot and restored when it is loaded, so hints match a loaded game.

## Where the app looks

When a game starts, the engine looks for the game's pack and hands it to the app. It checks these places, in order:

1. `<game folder>/learn_pack.json`
2. `<save folder>/learn_<gameid>.json`

If it finds neither, the app uses its built-in pack (the Curse of Monkey Island demo).

Progress (lines seen, hints shown, hint timer) is stored per game.

## Making a pack for a new game (SCUMM v7/v8: CMI, Full Throttle, The Dig)

```sh
python3 extract_lines.py "<game folder>" -o work/lines.json      # every line, with its ID
python3 split_batches.py work/lines.json work/batches --size 70  # batches of ~70-100 lines
# translate each batch -> work/out/batch_NNN.json  ({id: hebrew}); see below
python3 -c "import json,glob;d={};[d.update(json.load(open(f))) for f in glob.glob('work/out/*.json')];json.dump(d,open('work/he_ids.json','w'),ensure_ascii=False)"
python3 make_pack.py --game comi --lines work/lines.json --he work/he_ids.json \
    --hints <game>/hints.json -o learn_pack.json
```

Lines that only appear inside videos (compressed .SAN files) are not found by `extract_lines.py`. The app logs them to `learn.jsonl` when they play, and they can be added from there.

**Do not commit the full game's lines or pack to the public repository.** They contain the game's script. Keep them next to the game on the tablet. (The demo is freely distributed, so its pack lives here.)

## Translating: what we measured on the demo (Sept 2026)

We had the 569 demo lines translated in three ways and compared them blind: the judge did not know which translation was which.

| Translator | Lines with a real meaning error | Notes |
|---|---|---|
| Hand translation (Opus, in the chat) | 15 of 482 flagged, ~6 real | the real errors are fixed in `he_ids.json` |
| Haiku, 6 subagents in parallel (~2.5 min) | 103 of 482 (21%) by the strict judge; 10 of 103 by the second judge | non-words, garbled Hebrew, lost idioms |
| Sonnet, Wally conversation only | 0 of 103 | chosen as best 27 times, against 46 for the hand translation and 10 for Haiku |

Two judges were used:

* **Judge 1** compared pairs: the hand translation against Haiku.
* **Judge 2** compared three versions (hand, Haiku, Sonnet) on the hardest batch, the Wally conversation.

Raw results are in `comi-demo/trial/`.

**Decision for the full game:**

1. Sonnet subagents translate the batches, several in parallel. They run inside the Claude session, so there is no API key and no extra cost.
2. A separate blind review pass flags meaning errors.
3. The flagged lines are fixed.

Haiku is not accurate enough for Hebrew subtitles. It is still fine for mechanical work, such as validating JSON or building word lists.

Every translator gets `TRANSLATE.md` (the rules) and `<game>/glossary.json` (fixed names and terms).
