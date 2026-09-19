"""GlassOS assistant — DeepSeek-V4.1-Flash (deepseek-flash) with tools + memory."""
from __future__ import annotations

import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable

DEEPSEEK_BASE = "https://api.deepseek.com"
DEFAULT_MODEL = "deepseek-flash"

SYSTEM = """Jesteś asystentem pracy GlassOS w okularach AR.
Odpowiadaj po polsku, krótko, konkretnie.
Pamiętasz historię rozmowy i fakty z pamięci.
Sterujesz pulpitami okularów przez narzędzia:
- switch_desktop: włącz pulpit po id lub nazwie
- set_layout: FOCUS, SINGLE, TWO_SBS, THREE_SBS, SINGLE_WIDE
- next_screen, recenter, zoom
- rename_desktop: nazwij okno
- remember: zapisz fakt o użytkowniku / pracy
- open_remote: otwórz komputer SSH/RDP po id
- list_files: pokaż ostatnie pliki z pulpitu PC
Nie obiecuj rzeczy, których nie możesz zrobić. Jeśli brakuje danych, zapytaj jednym zdaniem.
"""

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "switch_desktop",
            "description": "Włącz pulpit na okularach",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "name": {"type": "string"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_layout",
            "description": "Zmień układ ekranów",
            "parameters": {
                "type": "object",
                "properties": {
                    "layout": {
                        "type": "string",
                        "enum": ["FOCUS", "SINGLE", "TWO_SBS", "THREE_SBS", "SINGLE_WIDE"],
                    }
                },
                "required": ["layout"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "next_screen",
            "description": "Skocz na następny ekran",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "recenter",
            "description": "Ustaw widok na wprost",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "zoom",
            "description": "Przybliż lub oddal pulpit",
            "parameters": {
                "type": "object",
                "properties": {"factor": {"type": "number"}},
                "required": ["factor"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "rename_desktop",
            "description": "Nazwij pulpit / okno",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "name": {"type": "string"},
                },
                "required": ["name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "remember",
            "description": "Zapisz fakt do pamięci asystenta",
            "parameters": {
                "type": "object",
                "properties": {"fact": {"type": "string"}},
                "required": ["fact"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_remote",
            "description": "Otwórz zdalny komputer (SSH komenda albo RDP na PC huba)",
            "parameters": {
                "type": "object",
                "properties": {"id": {"type": "string"}},
                "required": ["id"],
            },
        },
    },
]


class Assistant:
    def __init__(
        self,
        get_state: Callable[[], dict],
        save_state: Callable[[dict], None],
        enqueue: Callable[[dict], dict],
        lock: Any,
        run_ssh: Callable[[dict, str], dict],
    ) -> None:
        self.get_state = get_state
        self.save_state = save_state
        self.enqueue = enqueue
        self.lock = lock
        self.run_ssh = run_ssh

    def key(self) -> str:
        return os.environ.get("DEEPSEEK_API_KEY", "").strip()

    def model(self) -> str:
        return os.environ.get("DEEPSEEK_MODEL") or DEFAULT_MODEL

    def ready(self) -> bool:
        return bool(self.key())

    def chat(self, text: str, jpeg: bytes | None = None) -> dict:
        text = (text or "").strip()
        if not text:
            return {"ok": False, "text": "Puste pytanie.", "applied": False}
        if not self.key():
            return {
                "ok": False,
                "text": "Brak DEEPSEEK_API_KEY w tools/hub/.env",
                "applied": False,
            }
        with self.lock:
            state = self.get_state()
            history = list(state.get("chat") or [])[-16:]
            memory = list(state.get("memory") or [])[-20:]
        messages = [{"role": "system", "content": SYSTEM}]
        if memory:
            facts = "\n".join(f"- {m.get('fact')}" for m in memory if m.get("fact"))
            messages.append({"role": "system", "content": "Pamięć:\n" + facts})
        for item in history:
            role = "assistant" if item.get("role") == "assistant" else "user"
            messages.append({"role": role, "content": str(item.get("text") or "")[:2000]})
        user_content: Any = text
        if jpeg:
            b64 = __import__("base64").b64encode(jpeg).decode("ascii")
            user_content = [
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                {"type": "text", "text": text},
            ]
        messages.append({"role": "user", "content": user_content})
        applied = False
        reply = ""
        try:
            data = self._complete(messages, tools=True)
            msg = (data.get("choices") or [{}])[0].get("message") or {}
            tool_calls = msg.get("tool_calls") or []
            if tool_calls:
                messages.append(msg)
                for call in tool_calls:
                    fn = (call.get("function") or {})
                    name = fn.get("name") or ""
                    try:
                        args = json.loads(fn.get("arguments") or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    result = self._run_tool(name, args)
                    applied = applied or bool(result.get("applied"))
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call.get("id") or name,
                            "content": json.dumps(result, ensure_ascii=False)[:1500],
                        }
                    )
                data2 = self._complete(messages, tools=False)
                reply = ((data2.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
            else:
                reply = msg.get("content") or ""
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "ignore")[:400]
            reply = f"DeepSeek HTTP {exc.code}: {body}"
        except Exception as exc:
            reply = f"Asystent: {exc}"
        reply = (reply or "").strip() or "Brak odpowiedzi."
        with self.lock:
            state = self.get_state()
            chat = state.setdefault("chat", [])
            chat.append({"role": "user", "text": text, "ts": time.time()})
            chat.append({"role": "assistant", "text": reply, "ts": time.time()})
            state["chat"] = chat[-80:]
            self.save_state(state)
        return {"ok": True, "text": reply, "applied": applied, "model": self.model()}

    def _complete(self, messages: list, tools: bool) -> dict:
        payload: dict[str, Any] = {
            "model": self.model(),
            "messages": messages,
            "temperature": 0.3,
        }
        if tools:
            payload["tools"] = TOOLS
        req = urllib.request.Request(
            f"{DEEPSEEK_BASE}/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.key()}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _run_tool(self, name: str, args: dict) -> dict:
        state = self.get_state()
        desks = list(state.get("desktops") or [])
        comps = list(state.get("computers") or [])
        if name == "switch_desktop":
            desk = self._find_desk(desks, args.get("id"), args.get("name"))
            if not desk:
                return {"ok": False, "error": "nie znam takiego pulpitu"}
            self.enqueue({"type": "apply_desktop", "desktopId": desk["id"]})
            return {"ok": True, "applied": True, "desktop": desk.get("name")}
        if name == "set_layout":
            layout = str(args.get("layout") or "TWO_SBS")
            self.enqueue({"type": "layout", "layout": layout})
            return {"ok": True, "applied": True, "layout": layout}
        if name == "next_screen":
            self.enqueue({"type": "next_screen"})
            return {"ok": True, "applied": True}
        if name == "recenter":
            self.enqueue({"type": "recenter"})
            self.enqueue({"type": "reset_look"})
            return {"ok": True, "applied": True}
        if name == "zoom":
            factor = float(args.get("factor") or 1.15)
            self.enqueue({"type": "zoom_in" if factor >= 1 else "zoom_out"})
            return {"ok": True, "applied": True, "factor": factor}
        if name == "rename_desktop":
            desk = self._find_desk(desks, args.get("id"), None) or (desks[0] if desks else None)
            if not desk:
                return {"ok": False, "error": "brak pulpitu"}
            desk["name"] = str(args.get("name") or desk.get("name"))
            with self.lock:
                st = self.get_state()
                for i, d in enumerate(st.get("desktops") or []):
                    if d.get("id") == desk["id"]:
                        st["desktops"][i] = desk
                self.save_state(st)
            return {"ok": True, "applied": True, "name": desk["name"]}
        if name == "remember":
            fact = str(args.get("fact") or "").strip()
            if not fact:
                return {"ok": False}
            with self.lock:
                st = self.get_state()
                mem = st.setdefault("memory", [])
                mem.append({"fact": fact, "ts": time.time()})
                st["memory"] = mem[-80:]
                self.save_state(st)
            return {"ok": True, "applied": True}
        if name == "open_remote":
            cid = str(args.get("id") or "")
            comp = next((c for c in comps if c.get("id") == cid), None)
            if not comp:
                return {"ok": False, "error": "brak komputera"}
            kind = (comp.get("kind") or "ssh").lower()
            if kind == "rdp":
                host = comp.get("host") or ""
                try:
                    subprocess.Popen(["mstsc", f"/v:{host}"], close_fds=True)
                    return {"ok": True, "applied": True, "opened": "rdp " + host}
                except Exception as exc:
                    return {"ok": False, "error": str(exc)}
            result = self.run_ssh(comp, "hostname && whoami")
            return {"ok": result.get("ok"), "applied": True, "ssh": result}
        if name == "list_files":
            return {"ok": True, "files": recent_files()}
        return {"ok": False, "error": f"nieznane narzędzie {name}"}

    def _find_desk(self, desks: list, desk_id: str | None, name: str | None) -> dict | None:
        if desk_id:
            for d in desks:
                if d.get("id") == desk_id:
                    return d
        if name:
            n = name.strip().lower()
            for d in desks:
                if str(d.get("name") or "").lower() == n:
                    return d
            for d in desks:
                if n in str(d.get("name") or "").lower():
                    return d
        return None


def recent_files(limit: int = 12) -> list[dict]:
    roots = [Path.home() / "Desktop", Path.home() / "Documents"]
    files: list[Path] = []
    for root in roots:
        if not root.exists():
            continue
        try:
            files.extend([p for p in root.iterdir() if p.is_file()])
        except OSError:
            continue
    files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    out = []
    for p in files[:limit]:
        out.append({"name": p.name, "path": str(p), "folder": p.parent.name})
    return out
