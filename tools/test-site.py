#!/usr/bin/env python3
"""The device check's test computer, as the test phone sees it at 10.0.2.2.

Serves the test films and pages from a folder, and stands in for Anthropic's Messages API so Superclean can be
tried end to end without a real key or any cost: the app talks to it only when its Claude key is the device
check's own made-up key. It answers with a made-up Parents Guide (after a pause, as a real web search can) and
made-up findings, and writes what it was sent, without the pictures themselves, to anthropic-requests.txt.

Usage: python3 tools/test-site.py PORT FOLDER
"""
import http.server
import json
import os
import sys
import threading

PORT = int(sys.argv[1])
ROOT = sys.argv[2]
KEY = "test-key-for-the-device-check"
lock = threading.Lock()
count = 0

GUIDE = {
    "title": "Tears of Steel", "year": "2012", "source": "IMDb",
    "sections": [
        {"name": "Sex & Nudity", "severity": "None", "items": []},
        {"name": "Violence & Gore", "severity": "Moderate", "items": [
            "Soldiers fight giant robots with guns, with several explosions.",
            "A man is shot by a robot at the end of the film; no blood is shown."]},
        {"name": "Profanity", "severity": "Mild", "items": ["One use of \"shit\"."]},
        {"name": "Alcohol, Drugs & Smoking", "severity": "None", "items": []},
        {"name": "Frightening & Intense Scenes", "severity": "Mild", "items": ["A robot attack on a bridge."]},
    ],
}


def text(answer):
    return {"type": "message", "role": "assistant", "content": [{"type": "text", "text": answer}], "stop_reason": "end_turn"}


def answer(request):
    system = request.get("system", "")
    messages = request.get("messages", [])
    if request.get("tools"):
        if len(messages) == 1:
            # Paused after a search, as a real turn with web search can be; the app sends it back to carry on.
            return {"type": "message", "role": "assistant", "stop_reason": "pause_turn", "content": [
                {"type": "text", "text": "I'll find the Parents Guide {on IMDb}."},
                {"type": "server_tool_use", "id": "srvtoolu_1", "name": "web_search", "input": {"query": "Tears of Steel parents guide"}},
                {"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": [
                    {"type": "web_search_result", "url": "https://www.imdb.com/title/tt2285752/parentalguide/",
                     "title": "Tears of Steel (2012) - Parents guide - IMDb", "encrypted_content": "made-up", "page_age": None}]}]}
        return text("Here it is:\n" + json.dumps(GUIDE))
    if "frames" in system:
        # Kissing was not chosen (the usual choices), so the app should leave it; the Parents Guide moment goes.
        return text(json.dumps({"flagged": [{"time": "0:00:04", "what": "kissing", "severity": 1},
                                            {"time": "0:00:10", "what": "guide", "severity": 2}]}))
    if "caption lines" in system:
        return text(json.dumps({"mute": [{"line": 2, "words": [], "whole": True}]}))
    return text("{}")


def describe(n, headers, request):
    parts = []
    for m in request.get("messages", []):
        content = m.get("content", [])
        kinds = [c.get("type") for c in content] if isinstance(content, list) else ["text"]
        parts.append(f"{m.get('role')}: " + ", ".join(f"{kinds.count(k)} {k}" for k in sorted(set(kinds))))
    return (f"--- request {n}: key {'ok' if headers.get('x-api-key') == KEY else 'WRONG'}, version {headers.get('anthropic-version')}, "
            f"model {request.get('model')}, max_tokens {request.get('max_tokens')}, "
            f"tools {[t.get('name') for t in request.get('tools', [])]}\n"
            f"messages: {' | '.join(parts)}\n"
            f"system: {request.get('system', '')}\n"
            f"last text: {last_text(request)[:3000]}\n")


def last_text(request):
    for m in reversed(request.get("messages", [])):
        content = m.get("content", [])
        if isinstance(content, list):
            for c in reversed(content):
                if c.get("type") == "text":
                    return c.get("text", "")
    return ""


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def do_POST(self):
        global count
        body = self.rfile.read(int(self.headers.get("content-length", 0)))
        with lock:
            count += 1
            n = count
        try:
            request = json.loads(body)
        except ValueError:
            request = {}
        with lock, open(os.path.join(ROOT, "anthropic-requests.txt"), "a") as log:
            log.write(describe(n, self.headers, request))
        if self.path != "/v1/messages" or self.headers.get("x-api-key") != KEY:
            reply, status = {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}, 401
        else:
            reply, status = answer(request), 200
        data = json.dumps(reply).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


http.server.ThreadingHTTPServer(("", PORT), Handler).serve_forever()
