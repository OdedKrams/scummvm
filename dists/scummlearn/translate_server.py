#!/usr/bin/env python3
"""ScummLearn translation service.

POST /translate  {"text": "Blast ye scurvy dogs!"}
  -> {"he": "...", "words": {"blast": {"he": "...", "note": "..."}, ...}}

Asks Claude for a child-friendly Hebrew translation of the sentence plus the
meaning of every word *in this sentence*, and caches the answer in SQLite, so
each game line costs one API call ever.

Standard library only. Run:
    ANTHROPIC_API_KEY=sk-... python3 translate_server.py --port 8787
Put it behind HTTPS (e.g. Caddy/nginx) and set that URL in the tablet panel.
"""
import argparse
import json
import os
import sqlite3
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = os.environ.get("SCUMMLEARN_MODEL", "claude-sonnet-5")
API_URL = "https://api.anthropic.com/v1/messages"
DB_PATH = os.environ.get("SCUMMLEARN_DB", "scummlearn-cache.sqlite3")

PROMPT = """You help Israeli children aged 7-9 understand English lines from a pirate adventure game (The Curse of Monkey Island).

Game line: {text}

Return ONLY a JSON object, no other text:
{{
  "he": "<natural, simple Hebrew translation a 9-year-old understands; keep names like Guybrush, Elaine, LeChuck in Hebrew letters>",
  "words": {{
    "<each distinct English word in the line, lowercase, without punctuation>": {{
      "he": "<its Hebrew meaning in THIS sentence, 1-3 words>",
      "note": "<optional short Hebrew hint for tricky words: pirate slang like 'ye'=you, 'fer'=for, idioms, past tense; else empty>"
    }}
  }}
}}"""

_db_lock = threading.Lock()


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.execute("CREATE TABLE IF NOT EXISTS cache (text TEXT PRIMARY KEY, result TEXT)")
    return conn


def cached(text):
    with _db_lock, db() as conn:
        row = conn.execute("SELECT result FROM cache WHERE text=?", (text,)).fetchone()
    return json.loads(row[0]) if row else None


def store(text, result):
    with _db_lock, db() as conn:
        conn.execute("INSERT OR REPLACE INTO cache VALUES (?, ?)", (text, json.dumps(result, ensure_ascii=False)))


def ask_claude(text):
    body = {
        "model": MODEL,
        "max_tokens": 1500,
        "messages": [{"role": "user", "content": PROMPT.format(text=text)}],
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(body).encode(),
        headers={
            "x-api-key": os.environ["ANTHROPIC_API_KEY"],
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        reply = json.load(r)
    out = "".join(b.get("text", "") for b in reply.get("content", []) if b.get("type") == "text").strip()
    start, end = out.find("{"), out.rfind("}")
    return json.loads(out[start:end + 1])


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self._send(204, {})

    def do_GET(self):
        self._send(200, {"ok": True, "model": MODEL})

    def do_POST(self):
        if self.path.rstrip("/") != "/translate":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            text = json.loads(self.rfile.read(n) or b"{}").get("text", "").strip()
        except Exception:
            return self._send(400, {"error": "bad json"})
        if not text or len(text) > 500:
            return self._send(400, {"error": "text required (max 500 chars)"})
        result = cached(text)
        if result is None:
            try:
                result = ask_claude(text)
            except Exception as e:
                return self._send(502, {"error": str(e)})
            store(text, result)
        self._send(200, result)

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    print(f"ScummLearn translate server on {a.host}:{a.port}, model {MODEL}", flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
