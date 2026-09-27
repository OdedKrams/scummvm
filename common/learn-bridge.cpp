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

#include "common/learn-bridge.h"
#include "common/config-manager.h"
#include "common/debug.h"
#include "common/file.h"
#include "common/fs.h"
#include "common/system.h"

namespace Common {

static LearnSink g_learnSink = nullptr;
static WriteStream *g_learnLog = nullptr;
static bool g_learnLogTried = false;
static String g_lastKey;

void setLearnSink(LearnSink sink) {
	g_learnSink = sink;
}

static bool learnEnabled() {
	if (!ConfMan.hasKey("learn_mode"))
		return true;
	return ConfMan.getBool("learn_mode");
}

static String jsonEscape(const String &s) {
	String out;
	for (uint i = 0; i < s.size(); ++i) {
		byte c = (byte)s[i];
		switch (c) {
		case '"':  out += "\\\""; break;
		case '\\': out += "\\\\"; break;
		case '\n': out += "\\n"; break;
		case '\r': break;
		case '\t': out += ' '; break;
		default:
			if (c < 0x20)
				continue;           // drop engine control codes
			if (c >= 0x80)
				out += String::format("\\u%04x", c); // Latin-1 -> JSON escape
			else
				out += (char)c;
		}
	}
	return out;
}

static void openLog() {
	g_learnLogTried = true;
	Path dir = ConfMan.getPath("savepath");
	Path file = dir.empty() ? Path("learn.jsonl") : dir.appendComponent("learn.jsonl");

	// Keep what earlier sessions wrote: read it back, then re-open for writing.
	String previous;
	{
		File in;
		if (in.open(FSNode(file))) {
			previous = in.readString(0, in.size());
			in.close();
		}
	}

	// Non-atomic: lines must survive even if the app is killed (Android does that).
	g_learnLog = FSNode(file).createWriteStream(false);
	if (!g_learnLog) {
		warning("ScummLearn: could not open %s", file.toString().c_str());
		return;
	}
	if (!previous.empty())
		g_learnLog->writeString(previous);
}

void learnEmit(const char *kind, const String &speaker, const String &text) {
	if (!learnEnabled())
		return;

	String trimmed = text;
	trimmed.trim();
	if (trimmed.empty())
		return;

	// Engines often redraw the same line every frame; report it once.
	String key = String(kind) + "|" + speaker + "|" + trimmed;
	if (key == g_lastKey)
		return;
	g_lastKey = key;

	String json = String::format("{\"t\":%u,\"game\":\"%s\",\"kind\":\"%s\",\"speaker\":\"%s\",\"text\":\"%s\"}",
		g_system->getMillis(),
		jsonEscape(ConfMan.getActiveDomainName()).c_str(),
		kind,
		jsonEscape(speaker).c_str(),
		jsonEscape(trimmed).c_str());

	debug(1, "LEARN %s", json.c_str());

	if (!g_learnLogTried)
		openLog();
	if (g_learnLog) {
		g_learnLog->writeString(json);
		g_learnLog->writeByte('\n');
		g_learnLog->flush();
	}

	if (g_learnSink)
		g_learnSink(json);
}

void learnClear() {
	if (!learnEnabled())
		return;
	// Let the same line be reported again next time it is spoken.
	g_lastKey.clear();
	debug(1, "LEARN {\"kind\":\"clear\"}");
	if (g_learnSink)
		g_learnSink("{\"kind\":\"clear\"}");
}

} // End of namespace Common
