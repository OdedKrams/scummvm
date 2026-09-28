# ScummLearn: learning English with classic adventure games

ScummLearn is a family project built on a fork of [ScummVM](https://www.scummvm.org).

Kids play classic LucasArts adventure games on an Android tablet. The game stays in English, spoken and written. A thin layer on top gives Hebrew help exactly when the kid wants it, and turns what they hear and see into small English games.

The first game is **The Curse of Monkey Island** (CMI). It was developed on the free demo, then extended to the full game (bought on GOG).

The branch is `scummlearn`. All ScummLearn changes live on it. Upstream ScummVM stays untouched apart from small hooks.

---

## What the kid gets

| Feature | How it works |
|---|---|
| **Hebrew subtitles** | Each spoken English line shows its Hebrew translation at the bottom. It disappears when the line ends. **Holding a finger on it** keeps it on screen and pauses the game. The עב/EN button turns subtitles on or off. |
| **Kid touch controls** | Tap = walk / default action. Slide = move the cursor. Hold = open the verb coin. Two-finger tap = inventory. There are also big buttons for the inventory (🎒) and for skipping a line (⏩). |
| **Object names** | While the finger is on an object, its English name and Hebrew translation appear. After a moment, the English name is **spoken aloud** (Android text-to-speech). |
| **Dialogue options panel** | When the hero is in a conversation, a side panel lists every option in Hebrew and English. Tapping an option **reads the English aloud** without choosing it. The resting finger doesn't pick options by accident. |
| **💡 Hints** | The next step for the current room, based on progress: lines the kid has already heard, and items the hero has carried. A free hint every 5 minutes, or 5 coins. The same hint again is free. |
| **🔎 "Find the …!"** | Every 2 minutes a card asks, and says aloud, e.g. *"Find the rope!"*. The kid finds it on screen with a tap, a lift or a short hold (sliding across doesn't count). After 45 seconds the Hebrew appears as help. The same object can be asked up to 3 times per room. There are no cards during conversations. |
| **🪙 Coins** | +3 for a find (+2 after the Hebrew help), +1 the first time the kid hears an object's name. Coins buy hints. Progress and coins are kept per game. |

---

## How it works

```
 ScummVM engine (C++)                         Android app (Java)
 ─────────────────────                        ──────────────────
 SCUMM hooks ──► Common::learnEmit(kind,…) ──JNI──► LearnPanel.addLine(json)
   dialog / video subtitle                          subtitles, hover names, TTS,
   line ended (clear)                               dialogue panel, hints,
   room change                                      find game, coins
   dialogue options (choices)
   object under the finger (+ overlapping ones)     KidTouch: touch → mouse events
   objects in the room, hero's inventory
 on game start: sends the game's learn pack ─────►  loads translations + hints
 learn.jsonl in the save folder (debug log)
```

* **`common/learn-bridge.{h,cpp}`** is the bridge. Every event becomes one JSON line. It strips line IDs (`/CANNON.065/`, `/WGSO001/`) but keeps them as `id` for exact translation. On game start it loads the game's **learn pack** and sends it to the app.
* **SCUMM hooks** live in `engines/scumm/`:
  * `string_v7.cpp`: subtitles
  * `smush/smush_player.cpp`: video subtitles
  * `actor.cpp`: line ended
  * `room.cpp`: room change
  * `verbs.cpp` and `scumm.cpp`: dialogue options, the object under the finger, the room's objects and the inventory
* **Android side** (`backends/platform/android/`):
  * `jni-android.*`: the JNI bridge
  * `org/scummvm/scummvm/LearnPanel.java`: all the overlay UI and game logic
  * `KidTouch.java`: touch controls
  * `ScummVMActivity.java` and `ScummVMEvents.java`: the wiring

### Learn packs (per game, offline)

Everything specific to one game is in a single `learn_pack.json`: line translations by ID and by text, a word list, and hints. The app looks for it in the **game folder**, then in the save folder, and falls back to the built-in CMI demo pack. See [`dists/scummlearn/README-packs.md`](dists/scummlearn/README-packs.md).

**The full game's pack is not in this repository**, because it contains the game's script. It lives next to the game on the tablet and the PC.

### Making a pack (SCUMM v7/v8 games)

1. `extract_lines.py <game folder>`: reads every line with its ID straight from the game files. The full CMI has 9,594 lines.
2. `split_batches.py`: splits the lines into batches of about 100.
3. Translation: done by Claude subagents in parallel, following [`TRANSLATE.md`](dists/scummlearn/TRANSLATE.md) (rules for kids' subtitles) and `comi/glossary.json` (fixed names).
   * A blind test on the demo showed Sonnet gives 0 meaning errors in 103 lines, against about 20% for Haiku. Details are in `dists/scummlearn/comi-demo/trial/`.
4. `make_pack.py`: merges the translations and the hints into `learn_pack.json`.

Hints come from the official strategy guide. It was read with OCR, the room numbers were matched with the room names from `COMI.LA0`, and each step got "done" markers (lines heard, or `has:item`). Chapter 1 is done; chapters 2–6 are still to do.

---

## Build and install

* **CI** (`.github/workflows/scummlearn.yml`): every push to `scummlearn` builds the Android APK (arm64, SCUMM engines only) and a Windows build.
  * The APK is signed with the fixed key in `dists/scummlearn/debug.keystore` (a plain debug key), so updates install over the previous version.
  * It is published to the release **`scummlearn-latest`**:
    https://github.com/OdedKrams/scummvm/releases/download/scummlearn-latest/ScummVM-debug.apk
* **`dists/scummlearn/debug.bat`** (Windows, with the tablet on USB and USB debugging on). It runs these steps:
  1. Downloads the Android platform-tools, if missing.
  2. Downloads and installs the latest APK. If the update is refused, it keeps the kid's progress, reinstalls, and restores the progress.
  3. Copies the full game (from `games\comi` next to the file) to the tablet once, and its learn pack every run.
  4. Starts the app and records the log while you play.
  5. When you press a key, collects `learn.jsonl`, logcat and a screenshot into `debug-logs\` for analysis.

## Status (September 2026)

* The features were built and tested on the tablet with the CMI demo. The latest changes (inventory-aware hints, the stricter find game, copying the full game) have not been tested on the tablet yet.
* The full game is translated; testing it on the tablet is next.
* Next steps:
  * Hints for chapters 2–6, with graded hints taken from the guide (a general push first, the exact answer last).
  * A blind review of the full translation.
  * More games: Full Throttle and The Dig use the same engine version.
