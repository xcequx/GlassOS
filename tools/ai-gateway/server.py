#!/usr/bin/env python3
"""GlassOS AI gateway — runs on the PC, phone streams Luma Pro frames here.

Compatible with VisualClaw live endpoints:
  GET  /health
  GET  /v1/live/status
  POST /v1/video/frame      JSON {session_id, image (base64), ts}
  POST /v1/glasses/chat     JSON {session_id, text}

If XAI_API_KEY is set, chat uses SpaceXAI (xAI) vision.
Without a key the gateway still accepts frames so you can verify the USB/camera path.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENV_FILE = ROOT / ".env"

XAI_BASE = "https://api.x.ai/v1"
DEFAULT_MODEL = "grok-4.5"
MAX_FRAMES = 4
MAX_IMAGE_BYTES = 1_500_000

SYSTEM_PROMPT = (
    "Jesteś asystentem w okularach AR VITURE Luma Pro. "
    "Widzisz to, na co patrzy użytkownik (kamera pass-through). "
    "Odpowiadaj po polsku, krótko (2–5 zdań), konkretnie. "
    "Jeśli to urządzenie, tabliczka, kod, UI albo dokument — odczytaj tekst i powiedz co z tym zrobić."
)


def load_env() -> None:
    if not ENV_FILE.exists():
        return
    for raw in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


class FrameBank:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._frames: dict[str, deque[bytes]] = defaultdict(lambda: deque(maxlen=MAX_FRAMES))
        self._count = 0
        self._last_ts = 0.0

    def add(self, session: str, jpeg: bytes) -> int:
        if len(jpeg) > MAX_IMAGE_BYTES:
            jpeg = jpeg[:MAX_IMAGE_BYTES]
        with self._lock:
            self._frames[session].append(jpeg)
            self._count += 1
            self._last_ts = time.time()
            return len(self._frames[session])

    def latest(self, session: str) -> bytes | None:
        with self._lock:
            bank = self._frames.get(session)
            if not bank:
                return None
            return bank[-1]

    def stats(self) -> dict:
        with self._lock:
            return {
                "frames_total": self._count,
                "sessions": len(self._frames),
                "last_frame_age_s": round(time.time() - self._last_ts, 2) if self._last_ts else None,
            }


FRAMES = FrameBank()
STARTED = time.time()


def xai_key() -> str:
    return os.environ.get("XAI_API_KEY", "").strip()


def xai_model() -> str:
    return os.environ.get("GLASSOS_AI_MODEL") or os.environ.get("XAI_MODEL") or DEFAULT_MODEL


def ask_xai(prompt: str, jpeg: bytes | None) -> str:
    key = xai_key()
    if not key:
        n = len(jpeg or b"")
        return (
            "Gateway działa, ale nie ma XAI_API_KEY. "
            f"Ostatnia klatka: {n} bajtów. "
            "Dodaj klucz w tools/ai-gateway/.env i zrestartuj serwer."
        )
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
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"xAI HTTP {exc.code}: {body[:400]}") from exc
    text = extract_output_text(data)
    if not text:
        raise RuntimeError("xAI zwróciło pustą odpowiedź")
    return text.strip()


def extract_output_text(data: dict) -> str:
    if isinstance(data.get("output_text"), str) and data["output_text"].strip():
        return data["output_text"]
    chunks: list[str] = []
    for item in data.get("output") or []:
        if not isinstance(item, dict):
            continue
        for part in item.get("content") or []:
            if not isinstance(part, dict):
                continue
            if part.get("type") in ("output_text", "text") and part.get("text"):
                chunks.append(str(part["text"]))
    return "\n".join(chunks)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        print(f"[gateway] {self.address_string()} {fmt % args}")

    def _send(self, code: int, obj: dict) -> None:
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/health"):
            self._send(
                200,
                {
                    "ok": True,
                    "status": "ok",
                    "service": "glassos-ai-gateway",
                    "model": xai_model() if xai_key() else "no-key",
                    "has_xai_key": bool(xai_key()),
                    "uptime_s": int(time.time() - STARTED),
                    **FRAMES.stats(),
                },
            )
            return
        if self.path.startswith("/v1/live/status"):
            self._send(
                200,
                {
                    "ok": True,
                    "glasses_http_enabled": True,
                    "gemini_live_enabled": False,
                    "xai_enabled": bool(xai_key()),
                    "model": xai_model() if xai_key() else None,
                    **FRAMES.stats(),
                },
            )
            return
        self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._send(400, {"error": "invalid json"})
            return
        if self.path == "/v1/video/frame":
            session = str(body.get("session_id") or "default")
            image = body.get("image") or body.get("data")
            if not image:
                self._send(400, {"error": "Missing 'image' field"})
                return
            if isinstance(image, str) and image.startswith("data:"):
                image = image.split(",", 1)[-1]
            try:
                jpeg = base64.b64decode(image)
            except Exception:
                self._send(400, {"error": "image is not base64"})
                return
            n = FRAMES.add(session, jpeg)
            print(f"[gateway] frame session={session} bytes={len(jpeg)} kept={n}")
            self._send(200, {"ok": True, "bytes": len(jpeg), "kept": n})
            return
        if self.path == "/v1/glasses/chat":
            session = str(body.get("session_id") or "default")
            text = str(body.get("text") or body.get("prompt") or "").strip()
            if not text:
                text = "Co widzę? Opisz krótko to, na co patrzę."
            jpeg = FRAMES.latest(session)
            extra = body.get("image")
            if extra and isinstance(extra, str):
                try:
                    jpeg = base64.b64decode(extra.split(",", 1)[-1])
                    FRAMES.add(session, jpeg)
                except Exception:
                    pass
            try:
                reply = ask_xai(text, jpeg)
            except Exception as exc:
                self._send(502, {"error": str(exc)})
                return
            print(f"[gateway] chat session={session} prompt={text[:60]!r} -> {reply[:80]!r}")
            self._send(200, {"ok": True, "text": reply, "reply": reply, "has_frame": jpeg is not None})
            return
        self._send(404, {"error": "not found"})


def main() -> None:
    load_env()
    parser = argparse.ArgumentParser(description="GlassOS AI gateway")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "30100")))
    args = parser.parse_args()
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    key_state = "XAI_API_KEY OK" if xai_key() else "BRAK XAI_API_KEY (tylko odbiór klatek)"
    print(f"GlassOS AI gateway  http://{args.host}:{args.port}")
    print(f"  {key_state}")
    print(f"  model={xai_model()}")
    print("  GET  /health")
    print("  POST /v1/video/frame")
    print("  POST /v1/glasses/chat")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstop")


if __name__ == "__main__":
    main()
