#!/usr/bin/env python3
"""GlassOS Hub — pulpit okularów z przeglądarki + (opcjonalnie) AI z kamery.

  http://PC:30100/           edytor pulpitów
  POST /v1/phone/hello       telefon zgłasza aplikacje i status
  POST /api/apply            włącz pulpit na okularach
  POST /v1/video/frame       klatki Luma Pro
  POST /v1/glasses/chat      pytanie o to, co widać
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from assistant import Assistant, recent_files

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
DATA = ROOT / "data"
PROJECT = ROOT.parent.parent
VERSION_FILE = STATIC / "version.json"
DATA.mkdir(exist_ok=True)
STATE_FILE = DATA / "state.json"
ENV_FILE = ROOT.parent / "ai-gateway" / ".env"
LOCAL_ENV = ROOT / ".env"

XAI_BASE = "https://api.x.ai/v1"
DEFAULT_MODEL = "grok-4.5"
MAX_FRAMES = 4
MAX_IMAGE_BYTES = 1_500_000
LOCK = threading.Lock()

DEFAULT_STATE = {
    "desktops": [
        {
            "id": "praca",
            "name": "Praca",
            "layout": "TWO_SBS",
            "screens": [[], []],
        },
        {
            "id": "focus",
            "name": "Focus",
            "layout": "FOCUS",
            "screens": [[]],
        },
        {
            "id": "kino",
            "name": "Kino",
            "layout": "SINGLE_WIDE",
            "screens": [[]],
        },
    ],
    "phone": {"apps": [], "glasses": {}, "seen": 0},
    "pending": [],
    "computers": [],
    "chat": [],
    "memory": [],
    "settings": {
        "voice": True,
        "tts": True,
        "default_layout": "TWO_SBS",
    },
}

SYSTEM_PROMPT = (
    "Jesteś asystentem GlassOS — pulpit w okularach VITURE, sterowany z komputera. "
    "Odpowiadaj po polsku, krótko i konkretnie. "
    "Możesz układać ekrany, pocztę, przeglądarkę i SSH. "
    "Gdy użytkownik chce zmianę pulpitu, na końcu odpowiedzi dodaj blok:\n"
    "```glassos\n"
    '{"layout":"TWO_SBS","screens":[[{"type":"mail","label":"Poczta"}],'
    '[{"type":"browser","label":"Web","url":"https://www.google.com"}]]}\n'
    "```\n"
    "Dozwolone type: mail, browser, ssh, app. layout: FOCUS, SINGLE, TWO_SBS, THREE_SBS, SINGLE_WIDE."
)


def find_apk() -> Path | None:
    for p in (
        STATIC / "GlassOS.apk",
        PROJECT / "GlassOS.apk",
        PROJECT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
    ):
        if p.exists():
            return p
    return None


def apk_manifest() -> dict:
    if VERSION_FILE.exists():
        try:
            data = json.loads(VERSION_FILE.read_text(encoding="utf-8-sig"))
            apk = find_apk()
            if apk and not data.get("size"):
                data["size"] = apk.stat().st_size
            return data
        except json.JSONDecodeError:
            pass
    apk = find_apk()
    return {
        "package": "com.uxspace",
        "versionName": "unknown",
        "versionCode": 0,
        "apk": "/GlassOS.apk",
        "sha256": "",
        "size": apk.stat().st_size if apk else 0,
        "present": apk is not None,
    }


def load_env() -> None:
    for path in (LOCAL_ENV, ENV_FILE):
        if not path.exists():
            continue
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            data = {}
    else:
        data = {}
    merged = json.loads(json.dumps(DEFAULT_STATE))
    merged.update(data)
    merged.setdefault("computers", [])
    merged.setdefault("chat", [])
    merged.setdefault("memory", [])
    merged.setdefault("settings", DEFAULT_STATE["settings"])
    merged.setdefault("desktops", DEFAULT_STATE["desktops"])
    merged.setdefault("pending", [])
    return merged


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


STATE = load_state()
STARTED = time.time()
FRAMES: dict[str, deque[bytes]] = defaultdict(lambda: deque(maxlen=MAX_FRAMES))
PREVIEW_JPEG = bytearray()
PREVIEW_AT = 0.0


ASSISTANT: Assistant | None = None


def xai_key() -> str:
    return os.environ.get("XAI_API_KEY", "").strip()


def deepseek_key() -> str:
    return os.environ.get("DEEPSEEK_API_KEY", "").strip()


def xai_model() -> str:
    return os.environ.get("GLASSOS_AI_MODEL") or DEFAULT_MODEL


def extract_output_text(data: dict) -> str:
    if isinstance(data.get("output_text"), str) and data["output_text"].strip():
        return data["output_text"]
    chunks: list[str] = []
    for item in data.get("output") or []:
        if not isinstance(item, dict):
            continue
        for part in item.get("content") or []:
            if isinstance(part, dict) and part.get("text"):
                chunks.append(str(part["text"]))
    return "\n".join(chunks)


def ask_xai(prompt: str, jpeg: bytes | None) -> str:
    key = xai_key()
    if not key:
        n = len(jpeg or b"")
        return f"Hub działa. Brak XAI_API_KEY. Ostatnia klatka: {n} B."
    content: list[dict] = []
    if jpeg:
        b64 = base64.b64encode(jpeg).decode("ascii")
        content.append(
            {
                "type": "input_image",
                "image_url": f"data:image/jpeg;base64,{b64}",
                "detail": "high",
            }
        )
    content.append({"type": "input_text", "text": prompt})
    payload = {
        "model": xai_model(),
        "input": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ],
    }
    req = urllib.request.Request(
        f"{XAI_BASE}/responses",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return extract_output_text(data).strip() or "Pusta odpowiedź modelu."


def enqueue(cmd: dict) -> dict:
    cmd = dict(cmd)
    cmd["id"] = cmd.get("id") or uuid.uuid4().hex[:10]
    with LOCK:
        STATE.setdefault("pending", []).append(cmd)
        save_state(STATE)
    return cmd


def computer_by_id(cid: str) -> dict | None:
    for c in STATE.get("computers") or []:
        if c.get("id") == cid:
            return c
    return None


def run_ssh(comp: dict, command: str) -> dict:
    host = (comp.get("host") or "").strip()
    user = (comp.get("user") or "").strip()
    port = int(comp.get("port") or 22)
    if not host:
        return {"ok": False, "out": "", "err": "brak hosta"}
    target = f"{user}@{host}" if user else host
    try:
        proc = subprocess.run(
            [
                "ssh",
                "-o",
                "BatchMode=yes",
                "-o",
                "StrictHostKeyChecking=accept-new",
                "-o",
                "ConnectTimeout=8",
                "-p",
                str(port),
                target,
                command,
            ],
            capture_output=True,
            text=True,
            timeout=40,
        )
        return {
            "ok": proc.returncode == 0,
            "out": proc.stdout[-8000:],
            "err": proc.stderr[-2000:],
            "code": proc.returncode,
        }
    except FileNotFoundError:
        return {"ok": False, "out": "", "err": "Brak klienta ssh na tym PC (zainstaluj OpenSSH)."}
    except Exception as exc:
        return {"ok": False, "out": "", "err": str(exc)}


def extract_glassos_block(text: str) -> dict | None:
    marker = "```glassos"
    start = text.find(marker)
    if start < 0:
        return None
    rest = text[start + len(marker) :]
    end = rest.find("```")
    if end < 0:
        return None
    raw = rest[:end].strip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def apply_ai_desktop(spec: dict) -> None:
    layout = spec.get("layout") or "TWO_SBS"
    screens = spec.get("screens") or []
    desk = {
        "id": "ai",
        "name": spec.get("name") or "AI",
        "layout": layout,
        "screens": screens,
    }
    with LOCK:
        desks = STATE.setdefault("desktops", [])
        for i, d in enumerate(desks):
            if d.get("id") == "ai":
                desks[i] = desk
                break
        else:
            desks.append(desk)
        save_state(STATE)
    enqueue({"type": "apply_desktop", "desktopId": "ai"})


SSH_PAGE = """<!DOCTYPE html>
<html lang="pl"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>SSH</title>
<style>
body{margin:0;background:#0b0e14;color:#e8eef6;font:15px/1.4 Segoe UI,sans-serif}
main{padding:16px}
h1{font-size:18px;color:#5ee0f7}
pre{background:#161b24;padding:12px;border-radius:12px;white-space:pre-wrap;min-height:180px}
input,button{font:inherit;padding:10px 12px;border-radius:10px;border:1px solid #2a3344;background:#1e2533;color:#e8eef6}
button{background:#5ee0f7;color:#00333c;font-weight:700;border:0}
.row{display:flex;gap:8px;margin:12px 0}
.err{color:#ff8a80}
</style></head>
<body><main>
<h1 id="title">SSH</h1>
<p id="meta" style="color:#93a0b4"></p>
<div class="row">
<input id="cmd" style="flex:1" value="uname -a" />
<button id="go">Uruchom</button>
</div>
<pre id="out">Gotowy.</pre>
<script>
const id = new URLSearchParams(location.search).get("id") || "";
async function load() {
  const s = await fetch("/api/state").then(r => r.json());
  const c = (s.computers || []).find(x => x.id === id);
  document.getElementById("title").textContent = c ? ("SSH · " + c.name) : "SSH";
  document.getElementById("meta").textContent = c ? ((c.user ? c.user+"@" : "") + c.host + ":" + (c.port||22)) : "brak komputera";
}
document.getElementById("go").onclick = async () => {
  const cmd = document.getElementById("cmd").value;
  document.getElementById("out").textContent = "…";
  const r = await fetch("/api/ssh/run", {method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({id, command: cmd})}).then(x => x.json());
  document.getElementById("out").textContent = (r.out || "") + (r.err ? ("\\n" + r.err) : "");
  document.getElementById("out").className = r.ok ? "" : "err";
};
load();
</script></main></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        print(f"[hub] {self.address_string()} {fmt % args}")

    def _json(self, code: int, obj: dict | list) -> None:
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def _bytes(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            html = (STATIC / "index.html").read_bytes()
            self._bytes(200, html, "text/html; charset=utf-8")
            return
        if path in ("/health", "/api/health"):
            glasses = STATE.get("phone", {}).get("glasses", {})
            self._json(
                200,
                {
                    "ok": True,
                    "status": "ok",
                    "service": "glassos-hub",
                    "model": (
                        os.environ.get("DEEPSEEK_MODEL")
                        if deepseek_key()
                        else (xai_model() if xai_key() else "no-key")
                    ),
                    "has_xai_key": bool(xai_key()),
                    "has_deepseek": bool(deepseek_key()),
                    "phone_online": time.time() - STATE.get("phone", {}).get("seen", 0) < 8,
                    "glasses": glasses,
                },
            )
            return
        if path == "/api/state":
            with LOCK:
                payload = dict(STATE)
                payload["files"] = recent_files()
                payload["ai"] = {
                    "deepseek": bool(deepseek_key()),
                    "model": os.environ.get("DEEPSEEK_MODEL") or "deepseek-flash",
                }
                self._json(200, payload)
            return
        if path == "/v1/live/status":
            self._json(200, {"ok": True, "glasses_http_enabled": True, "xai_enabled": bool(xai_key())})
            return
        if path == "/api/version":
            self._json(200, apk_manifest())
            return
        if path in ("/GlassOS.apk", "/app-debug.apk"):
            apk = find_apk()
            if apk is None:
                self._json(404, {"error": "brak GlassOS.apk — wrzuć plik do tools/hub/static/"})
                return
            data = apk.read_bytes()
            self._bytes(200, data, "application/vnd.android.package-archive")
            return
        if path == "/preview.jpg":
            with LOCK:
                data = bytes(PREVIEW_JPEG)
            if not data:
                self._json(404, {"error": "brak podglądu — otwórz GlassOS i podłącz okulary"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/view/ssh":
            self._bytes(200, SSH_PAGE.encode("utf-8"), "text/html; charset=utf-8")
            return
        if path == "/view/mail":
            self.send_response(302)
            self.send_header("Location", "https://mail.google.com")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        if path == "/api/preview":
            global PREVIEW_JPEG, PREVIEW_AT
            with LOCK:
                PREVIEW_JPEG = bytearray(raw)
                PREVIEW_AT = time.time()
            self._json(200, {"ok": True, "bytes": len(raw)})
            return
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid json"})
            return
        if path == "/v1/phone/hello":
            with LOCK:
                STATE["phone"] = {
                    "apps": body.get("apps") or [],
                    "glasses": body.get("glasses") or {},
                    "seen": time.time(),
                }
                save_state(STATE)
            self._json(200, {"ok": True, "pending": len(STATE.get("pending") or [])})
            return
        if path == "/api/desktops":
            desk = {
                "id": uuid.uuid4().hex[:8],
                "name": body.get("name") or "Nowy pulpit",
                "layout": body.get("layout") or "TWO_SBS",
                "screens": body.get("screens") or [[]],
            }
            with LOCK:
                STATE.setdefault("desktops", []).append(desk)
                save_state(STATE)
            self._json(200, desk)
            return
        if path == "/api/apply":
            cmd = enqueue({"type": "apply_desktop", "desktopId": body.get("id")})
            self._json(200, cmd)
            return
        if path == "/api/command":
            cmd = enqueue(body)
            self._json(200, cmd)
            return
        if path == "/api/ack":
            cid = body.get("id")
            with LOCK:
                STATE["pending"] = [c for c in STATE.get("pending") or [] if c.get("id") != cid]
                save_state(STATE)
            self._json(200, {"ok": True})
            return
        if path == "/v1/video/frame":
            session = str(body.get("session_id") or "default")
            image = body.get("image") or ""
            if isinstance(image, str) and image.startswith("data:"):
                image = image.split(",", 1)[-1]
            jpeg = base64.b64decode(image) if image else b""
            if jpeg:
                FRAMES[session].append(jpeg[:MAX_IMAGE_BYTES])
            self._json(200, {"ok": True, "bytes": len(jpeg)})
            return
        if path == "/v1/glasses/chat":
            session = str(body.get("session_id") or "default")
            text = str(body.get("text") or "Co widzę?").strip()
            jpeg = FRAMES[session][-1] if FRAMES[session] else None
            try:
                reply = ask_xai(text, jpeg)
            except Exception as exc:
                self._json(502, {"error": str(exc)})
                return
            self._json(200, {"ok": True, "text": reply, "reply": reply})
            return
        if path == "/api/computers":
            comp = {
                "id": uuid.uuid4().hex[:8],
                "name": body.get("name") or body.get("host") or "Komputer",
                "host": body.get("host") or "",
                "user": body.get("user") or "",
                "port": int(body.get("port") or (3389 if body.get("kind") == "rdp" else 22)),
                "kind": body.get("kind") or "ssh",
            }
            with LOCK:
                STATE.setdefault("computers", []).append(comp)
                save_state(STATE)
            self._json(200, comp)
            return
        if path == "/api/ssh/run":
            cid = str(body.get("id") or "")
            command = str(body.get("command") or "").strip() or "echo ok"
            comp = computer_by_id(cid)
            if not comp:
                self._json(404, {"ok": False, "err": "nieznany komputer"})
                return
            result = run_ssh(comp, command)
            self._json(200, result)
            return
        if path == "/api/ai/chat":
            text = str(body.get("text") or "").strip()
            if not text:
                self._json(400, {"error": "puste pytanie"})
                return
            jpeg = bytes(PREVIEW_JPEG) if PREVIEW_JPEG else None
            if ASSISTANT and ASSISTANT.ready():
                result = ASSISTANT.chat(text, jpeg)
                self._json(200, result)
                return
            try:
                reply = ask_xai(text, jpeg)
            except Exception as exc:
                self._json(502, {"error": str(exc)})
                return
            spec = extract_glassos_block(reply)
            applied = False
            if spec:
                apply_ai_desktop(spec)
                applied = True
            with LOCK:
                chat = STATE.setdefault("chat", [])
                chat.append({"role": "user", "text": text, "ts": time.time()})
                chat.append({"role": "assistant", "text": reply, "ts": time.time()})
                STATE["chat"] = chat[-80:]
                save_state(STATE)
            self._json(200, {"ok": True, "text": reply, "applied": applied})
            return
        if path == "/api/settings":
            with LOCK:
                cur = dict(STATE.get("settings") or {})
                cur.update(body)
                STATE["settings"] = cur
                save_state(STATE)
            self._json(200, STATE["settings"])
            return
        if path == "/api/remote/open":
            cid = str(body.get("id") or "")
            comp = computer_by_id(cid)
            if not comp:
                self._json(404, {"error": "brak komputera"})
                return
            kind = (comp.get("kind") or "ssh").lower()
            if kind == "rdp":
                host = str(comp.get("host") or "")
                try:
                    subprocess.Popen(["mstsc", f"/v:{host}"])
                    self._json(200, {"ok": True, "opened": "rdp", "host": host})
                except Exception as exc:
                    self._json(500, {"ok": False, "error": str(exc)})
                return
            result = run_ssh(comp, str(body.get("command") or "hostname"))
            self._json(200, result)
            return
        self._json(404, {"error": "not found"})

    def do_PUT(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if not path.startswith("/api/desktops/"):
            self._json(404, {"error": "not found"})
            return
        desk_id = path.rsplit("/", 1)[-1]
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        with LOCK:
            desks = STATE.setdefault("desktops", [])
            for i, d in enumerate(desks):
                if d.get("id") == desk_id:
                    body["id"] = desk_id
                    desks[i] = body
                    save_state(STATE)
                    self._json(200, body)
                    return
        self._json(404, {"error": "desktop not found"})


def main() -> None:
    load_env()
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "30100")))
    args = parser.parse_args()
    print(f"GlassOS Hub  http://{args.host}:{args.port}")
    print("  Otwórz tę stronę w przeglądarce i układaj pulpit okularów.")
    global ASSISTANT
    ASSISTANT = Assistant(
        get_state=lambda: STATE,
        save_state=save_state,
        enqueue=enqueue,
        lock=LOCK,
        run_ssh=run_ssh,
    )
    ai_label = (
        f"DeepSeek {os.environ.get('DEEPSEEK_MODEL') or 'deepseek-flash'} OK"
        if deepseek_key()
        else ("XAI_API_KEY OK" if xai_key() else "bez klucza")
    )
    print(f"  AI: {ai_label}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
