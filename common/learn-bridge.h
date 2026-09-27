/* ScummVM - Graphic Adventure Engine
 *
 * ScummVM is the legal property of its developers, whose names
 * are too numerous to list here. Please refer to the COPYRIGHT
 * file distributed with this source distribution.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

#ifndef COMMON_LEARN_BRIDGE_H
#define COMMON_LEARN_BRIDGE_H

#include "common/str.h"

namespace Common {

/**
 * Learning bridge (ScummLearn fork).
 *
 * Engines call learnEmit() whenever a line of in-game text is shown
 * (dialogue subtitle, object name, ...). Each line is serialised as one
 * JSON object and:
 *   - appended to <savepath>/learn.jsonl (for replay and desktop dev),
 *   - passed to the platform sink, if one is registered (Android: JNI -> help panel).
 *
 * Enabled by the config key "learn_mode" (default: on in this fork).
 */

typedef void (*LearnSink)(const String &json);

/** Register the platform sink (called by the backend at startup). */
void setLearnSink(LearnSink sink);

/**
 * Report a line of text shown by the game.
 * @param kind     "dialog", "object", "ui", ...
 * @param speaker  talking character name, may be empty
 * @param text     the text itself (in the game's encoding, usually ASCII for English)
 */
void learnEmit(const char *kind, const String &speaker, const String &text);

} // End of namespace Common

#endif
