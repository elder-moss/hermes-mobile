#!/usr/bin/env python3
"""MemS sidecar: the app-facing HTTP wrapper around Hermes' MemS plugin.

Deliberately thin: every write goes through the plugin's own
MemSProvider.handle_tool_call, so dedupe, supersession, retraction, history
and Ollama re-embedding behave exactly as they do for the agent. The only raw
SQL here is a read-only single-record SELECT (GET /api/mems/<id>).

Auth: Authorization: Bearer <token from ~/.hermes/mems-api/token>.
Bind: 127.0.0.1 only; expose via tailscale serve.
"""
import importlib.util
import json
import os
import sqlite3
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERMES_HOME = Path(os.environ.get("HERMES_HOME", "/home/enrich/.hermes"))
STATE_DIR = HERMES_HOME / "mems-api"
PORT = int(os.environ.get("MEMS_API_PORT", "9471"))
AGENT_SRC = HERMES_HOME / "hermes-agent"

sys.path.insert(0, str(AGENT_SRC))
os.environ.setdefault("HERMES_HOME", str(HERMES_HOME))

_spec = importlib.util.spec_from_file_location("mems_plugin", HERMES_HOME / "plugins" / "mems" / "__init__.py")
assert _spec is not None and _spec.loader is not None
_plugin = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_plugin)
provider = _plugin.MemSProvider()
provider.initialize("sidecar", hermes_home=str(HERMES_HOME))

with open(STATE_DIR / "token") as f:
    TOKEN = f.read().strip()


def record_by_id(memory_id):
    """Read-only lookup so add/correct can echo the stored record back."""
    with provider._db() as db:
        row = db.execute("SELECT * FROM memories WHERE id=?", (memory_id,)).fetchone()
        return dict(row) if row else None


class Handler(BaseHTTPRequestHandler):
    server_version = "mems-api/1.0"

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {TOKEN}"

    def _tool(self, action, args):
        """Run the plugin's own tool path; returns (ok, payload)."""
        result = provider.handle_tool_call("mems", {"action": action, **args})
        data = json.loads(result)
        if isinstance(data, dict) and data.get("error"):
            return False, data
        return True, data

    def do_GET(self):
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})
        path = self.path.split("?", 1)[0]
        query = {}
        if "?" in self.path:
            for pair in self.path.split("?", 1)[1].split("&"):
                k, _, v = pair.partition("=")
                query[k] = v.replace("%20", " ").replace("%2F", "/")
        try:
            if path == "/api/mems/list":
                ok, data = self._tool("list", {})
            elif path == "/api/mems/search":
                args = {"query": query.get("q", "")}
                if query.get("as_of"):
                    args["as_of"] = query["as_of"]
                ok, data = self._tool("search", args)
            elif path.startswith("/api/mems/") and path.rstrip("/").split("/")[-1].isdigit():
                row = record_by_id(int(path.rstrip("/").split("/")[-1]))
                ok, data = (row is not None), (row or {"error": "not found"})
            else:
                return self._send(404, {"error": "unknown route"})
            self._send(200 if ok else 400, data)
        except Exception as exc:  # noqa: BLE001 - report, never crash the thread
            self._send(500, {"error": str(exc)})

    def do_POST(self):
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})
        path = self.path.split("?", 1)[0]
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            if path == "/api/mems/add":
                ok, data = self._tool("add", body)
                if ok and data.get("saved"):
                    data = record_by_id(data["id"]) or data
            elif path == "/api/mems/correct":
                memory_id = int(body.pop("id"))
                ok, data = self._tool("correct", {"id": memory_id, **body})
                if ok and data.get("corrected"):
                    data = record_by_id(data["new_id"]) or data
            elif path == "/api/mems/retract":
                memory_id = int(body.get("id"))
                ok, data = self._tool("retract", {"id": memory_id})
                if ok:
                    data = {"retracted": True, "id": memory_id}
            else:
                return self._send(404, {"error": "unknown route"})
            self._send(200 if ok else 400, data)
        except (ValueError, KeyError) as exc:
            self._send(400, {"error": f"bad request: {exc}"})
        except Exception as exc:  # noqa: BLE001
            self._send(500, {"error": str(exc)})

    def log_message(self, format, *args):  # noqa: A002 - stdlib signature
        pass


def main():
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
