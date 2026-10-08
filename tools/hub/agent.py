"""GlassOS Agent — asystent roboczy z narzędziami, sterowany z okularów.

Pomysł zapożyczony z sam-siavoshian/claude-code-g2 (Claude Code na okularach Even
Realities G2): agent, który naprawdę wykonuje pracę na komputerze, a jego strumień
zdarzeń ląduje przed oczami. Tam było CLI `claude` + Whisper + HUD 576×288; tutaj
silnikiem jest DeepSeek, ekranem — pełny 1080p pulpit w Luma Pro, a wejściem głos
z przeglądarki albo klawiatura.

Bezpieczeństwo jest tu pierwszorzędne, bo to są polecenia na prawdziwym komputerze:

* praca tylko wewnątrz katalogów z białej listy (`AGENT_ROOTS`),
* polecenia tylko-do-odczytu lecą od razu, cała reszta czeka na Twoje kliknięcie,
* kilka wzorców (kasowanie dysku, formatowanie, wyłączanie maszyny) jest odrzucanych
  bez pytania — agent nie ma powodu ich potrzebować.
"""
from __future__ import annotations

import json
import os
import queue
import re
import shlex
import subprocess
import threading
import time
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

DEEPSEEK_BASE = "https://api.deepseek.com"
DEFAULT_MODEL = "deepseek-flash"
MAX_STEPS = 12
CMD_TIMEOUT_S = 120
MAX_OUTPUT_CHARS = 6000

SYSTEM = """Jesteś agentem roboczym GlassOS. Pracujesz na komputerze użytkownika i
pokazujesz postęp na ekranie w okularach.

Zasady:
- Działaj, nie opowiadaj. Masz narzędzia — użyj ich, zamiast tłumaczyć, co dałoby się zrobić.
- Jedno narzędzie na krok, potem przeczytaj wynik i zdecyduj, co dalej.
- Polecenia zmieniające cokolwiek trafiają do akceptacji użytkownika. To normalne — wyślij je
  i czekaj na wynik, nie kombinuj obejść.
- Odpowiadaj po polsku, krótko. Na ekranie okularów długie akapity są nieczytelne.
- Gdy skończysz, napisz jedno zdanie podsumowania: co zrobiłeś i co z tego wyszło.
- Nie zgaduj zawartości plików — przeczytaj je.

Środowisko: Windows. `run` idzie przez `cmd.exe`, więc używaj `dir`, `type`, `findstr`,
`where`. Uniksowe `ls`, `grep`, `wc` tam nie istnieją — do szukania w plikach masz
narzędzie `search`, do listowania `list_dir`. Gradle wołaj jako `gradlew.bat`.
"""

# Polecenia, które tylko patrzą. Wszystko inne przechodzi przez akceptację.
READ_ONLY = (
    "git status", "git diff", "git log", "git show", "git branch", "git remote",
    "ls", "dir", "cat", "type", "head", "tail", "find", "findstr", "grep", "rg",
    "wc", "du", "df", "pwd", "whoami", "hostname", "date", "python --version",
    "python -V", "pip list", "node --version", "npm ls", "curl -s", "tree",
    "adb devices", "gradlew tasks", "./gradlew tasks",
)

# Rzeczy, których agent nie zrobi nawet po akceptacji.
FORBIDDEN = (
    r"\brm\s+-rf\s+/(?!\w)", r"\bmkfs\b", r"\bformat\s+[a-z]:", r"\bdiskpart\b",
    r"\bshutdown\b", r"\breboot\b", r"\bdel\s+/[sf]\b.*\\\*", r":\(\)\{.*\};:",
    r"\bReg(istry)?\s+delete\b", r"\bcipher\s+/w\b",
)


def decode_console(raw: bytes | None) -> str:
    """Bytes from cmd.exe into text, trying the console page before UTF-8."""
    if not raw:
        return ""
    for enc in (os.environ.get("PYTHONIOENCODING"), "utf-8", "cp852", "cp1250", "latin-1"):
        if not enc:
            continue
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def roots() -> list[Path]:
    """Katalogi, w których agent może pracować."""
    raw = os.environ.get("AGENT_ROOTS", "")
    out: list[Path] = []
    for part in raw.split(os.pathsep):
        part = part.strip()
        if not part:
            continue
        p = Path(part).expanduser()
        if p.is_dir():
            out.append(p.resolve())
    if not out:
        # Domyślnie: samo repo GlassOS. Świadoma decyzja — agent ma pomagać w tym
        # projekcie, a nie mieć wolną rękę na całym dysku.
        out.append(Path(__file__).resolve().parents[2])
    return out


def inside_roots(path: Path) -> bool:
    try:
        resolved = path.resolve()
    except OSError:
        return False
    for root in roots():
        try:
            resolved.relative_to(root)
            return True
        except ValueError:
            continue
    return False


def is_read_only(command: str) -> bool:
    c = command.strip().lower()
    return any(c.startswith(prefix) for prefix in READ_ONLY)


def is_forbidden(command: str) -> str | None:
    c = command.strip()
    for pattern in FORBIDDEN:
        if re.search(pattern, c, re.IGNORECASE):
            return pattern
    return None


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "run",
            "description": (
                "Uruchom polecenie powłoki na komputerze użytkownika. Polecenia tylko "
                "odczytujące wykonują się od razu; zmieniające czekają na akceptację."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string"},
                    "cwd": {"type": "string", "description": "Katalog roboczy (w białej liście)"},
                    "why": {"type": "string", "description": "Po co to polecenie — jedno zdanie"},
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Przeczytaj plik tekstowy (lub jego fragment).",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "start": {"type": "integer", "description": "Pierwsza linia (od 1)"},
                    "lines": {"type": "integer", "description": "Ile linii, domyślnie 200"},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Zapisz plik. Zawsze wymaga akceptacji użytkownika.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                    "why": {"type": "string"},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_dir",
            "description": "Pokaż zawartość katalogu.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search",
            "description": "Znajdź tekst w plikach (rekurencyjnie).",
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": {"type": "string"},
                    "path": {"type": "string"},
                    "glob": {"type": "string", "description": "np. *.kt"},
                },
                "required": ["pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "glasses",
            "description": (
                "Steruj pulpitem w okularach: switch_desktop, layout, next_screen, "
                "recenter, fit_screens, taskbar_toggle."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {"type": "string"},
                    "value": {"type": "string"},
                },
                "required": ["action"],
            },
        },
    },
]


class Run:
    """Jedno zadanie agenta: kolejka zdarzeń + ewentualna prośba o akceptację."""

    def __init__(self, run_id: str, prompt: str) -> None:
        self.id = run_id
        self.prompt = prompt
        self.events: queue.Queue[dict] = queue.Queue()
        self.history: list[dict] = []
        self.pending: dict | None = None
        self.decision: queue.Queue[bool] = queue.Queue(maxsize=1)
        self.done = False
        self.started = time.time()

    def emit(self, kind: str, **fields: Any) -> None:
        event = {"kind": kind, "ts": time.time(), **fields}
        self.history.append(event)
        self.events.put(event)


class Agent:
    def __init__(self, enqueue: Callable[[dict], dict], get_state: Callable[[], dict]) -> None:
        self.enqueue = enqueue
        self.get_state = get_state
        self.runs: dict[str, Run] = {}
        self.lock = threading.Lock()

    # ---------------------------------------------------------------- lifecycle

    def key(self) -> str:
        return os.environ.get("DEEPSEEK_API_KEY", "").strip()

    def model(self) -> str:
        return os.environ.get("AGENT_MODEL") or os.environ.get("DEEPSEEK_MODEL") or DEFAULT_MODEL

    def start(self, prompt: str) -> Run:
        run = Run(uuid.uuid4().hex[:10], prompt)
        with self.lock:
            self.runs[run.id] = run
            # Trzymamy ostatnie kilka przebiegów — reszta idzie w niepamięć.
            for old in list(self.runs)[:-6]:
                if self.runs[old].done:
                    self.runs.pop(old, None)
        threading.Thread(target=self._loop, args=(run,), name=f"agent-{run.id}", daemon=True).start()
        return run

    def get(self, run_id: str) -> Run | None:
        return self.runs.get(run_id)

    def approve(self, run_id: str, ok: bool) -> bool:
        run = self.runs.get(run_id)
        if not run or not run.pending:
            return False
        try:
            run.decision.put_nowait(ok)
        except queue.Full:
            return False
        return True

    # -------------------------------------------------------------------- pętla

    def _loop(self, run: Run) -> None:
        if not self.key():
            run.emit("error", text="Brak DEEPSEEK_API_KEY w tools/hub/.env")
            run.emit("done", text="")
            run.done = True
            return
        messages: list[dict] = [
            {"role": "system", "content": SYSTEM},
            {"role": "system", "content": "Katalogi robocze: " + ", ".join(str(r) for r in roots())},
            {"role": "user", "content": run.prompt},
        ]
        run.emit("start", text=run.prompt)
        try:
            for step in range(MAX_STEPS):
                data = self._complete(messages)
                msg = (data.get("choices") or [{}])[0].get("message") or {}
                text = (msg.get("content") or "").strip()
                calls = msg.get("tool_calls") or []
                if text:
                    run.emit("text", text=text)
                if not calls:
                    run.emit("done", text=text)
                    run.done = True
                    return
                messages.append(msg)
                for call in calls:
                    fn = call.get("function") or {}
                    name = fn.get("name") or ""
                    try:
                        args = json.loads(fn.get("arguments") or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    result = self._tool(run, name, args)
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call.get("id") or name,
                            "content": json.dumps(result, ensure_ascii=False)[:MAX_OUTPUT_CHARS],
                        }
                    )
            run.emit("done", text=f"Zatrzymuję się po {MAX_STEPS} krokach.")
        except Exception as exc:  # noqa: BLE001 — agent nie może ubić serwera
            run.emit("error", text=str(exc)[:400])
            run.emit("done", text="")
        finally:
            run.done = True

    def _complete(self, messages: list[dict]) -> dict:
        payload = {
            "model": self.model(),
            "messages": messages,
            "tools": TOOLS,
            "temperature": 0.2,
        }
        req = urllib.request.Request(
            f"{DEEPSEEK_BASE}/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.key()}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))

    # ----------------------------------------------------------------- narzędzia

    def _tool(self, run: Run, name: str, args: dict) -> dict:
        if name == "run":
            return self._run_command(run, args)
        if name == "read_file":
            return self._read_file(run, args)
        if name == "write_file":
            return self._write_file(run, args)
        if name == "list_dir":
            return self._list_dir(run, args)
        if name == "search":
            return self._search(run, args)
        if name == "glasses":
            return self._glasses(run, args)
        return {"ok": False, "error": f"nieznane narzędzie {name}"}

    def _ask_approval(self, run: Run, kind: str, summary: str, detail: str) -> bool:
        run.pending = {"what": kind, "summary": summary, "detail": detail}
        # "what", nie "kind": emit() używa "kind" na typ zdarzenia i drugi taki
        # argument wysadzał cały przebieg.
        run.emit("approval", what=kind, summary=summary, detail=detail)
        try:
            ok = run.decision.get(timeout=300)
        except queue.Empty:
            ok = False
            run.emit("note", text="Brak decyzji przez 5 minut — pomijam.")
        run.pending = None
        run.emit("approval_done", approved=ok, summary=summary)
        return ok

    def _resolve(self, raw: str | None, fallback: Path | None = None) -> Path | None:
        if not raw:
            return fallback or roots()[0]
        p = Path(raw).expanduser()
        if not p.is_absolute():
            p = (roots()[0] / p)
        return p if inside_roots(p) else None

    def _run_command(self, run: Run, args: dict) -> dict:
        command = str(args.get("command") or "").strip()
        if not command:
            return {"ok": False, "error": "puste polecenie"}
        blocked = is_forbidden(command)
        if blocked:
            run.emit("blocked", text=command)
            return {"ok": False, "error": "polecenie odrzucone jako niebezpieczne"}
        cwd = self._resolve(args.get("cwd"))
        if cwd is None:
            return {"ok": False, "error": "katalog poza białą listą"}
        if not is_read_only(command):
            why = str(args.get("why") or "")
            if not self._ask_approval(run, "run", command, f"{cwd}\n{why}"):
                return {"ok": False, "error": "użytkownik odrzucił polecenie"}
        run.emit("tool", tool="run", text=command, cwd=str(cwd))
        try:
            proc = subprocess.run(
                command,
                shell=True,
                cwd=str(cwd),
                capture_output=True,
                timeout=CMD_TIMEOUT_S,
            )
            # cmd.exe pisze w kodowaniu konsoli, nie w UTF-8 — bez tego polskie znaki
            # w wyniku polecenia dojeżdżają do modelu jako krzaki.
            out = decode_console(proc.stdout)[-MAX_OUTPUT_CHARS:]
            err = decode_console(proc.stderr)[-2000:]
            run.emit("result", tool="run", code=proc.returncode, text=(out or err)[-1500:])
            return {"ok": proc.returncode == 0, "code": proc.returncode, "stdout": out, "stderr": err}
        except subprocess.TimeoutExpired:
            run.emit("result", tool="run", code=-1, text=f"timeout po {CMD_TIMEOUT_S}s")
            return {"ok": False, "error": f"timeout po {CMD_TIMEOUT_S}s"}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": str(exc)}

    def _read_file(self, run: Run, args: dict) -> dict:
        path = self._resolve(args.get("path"))
        if path is None or not path.is_file():
            return {"ok": False, "error": "plik poza białą listą albo nie istnieje"}
        start = max(1, int(args.get("start") or 1))
        count = max(1, min(int(args.get("lines") or 200), 800))
        try:
            text = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError as exc:
            return {"ok": False, "error": str(exc)}
        chunk = text[start - 1 : start - 1 + count]
        run.emit("tool", tool="read_file", text=f"{path.name}: linie {start}–{start + len(chunk) - 1}")
        return {"ok": True, "path": str(path), "total_lines": len(text), "content": "\n".join(chunk)[:MAX_OUTPUT_CHARS]}

    def _write_file(self, run: Run, args: dict) -> dict:
        path = self._resolve(args.get("path"))
        if path is None:
            return {"ok": False, "error": "ścieżka poza białą listą"}
        content = str(args.get("content") or "")
        preview = content[:600] + ("…" if len(content) > 600 else "")
        summary = f"zapis {path} ({len(content)} znaków)"
        if not self._ask_approval(run, "write", summary, preview):
            return {"ok": False, "error": "użytkownik odrzucił zapis"}
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        except OSError as exc:
            return {"ok": False, "error": str(exc)}
        run.emit("result", tool="write_file", code=0, text=summary)
        return {"ok": True, "path": str(path), "bytes": len(content)}

    def _list_dir(self, run: Run, args: dict) -> dict:
        path = self._resolve(args.get("path"))
        if path is None or not path.is_dir():
            return {"ok": False, "error": "katalog poza białą listą albo nie istnieje"}
        items = []
        for entry in sorted(path.iterdir())[:200]:
            items.append(("dir " if entry.is_dir() else "file ") + entry.name)
        run.emit("tool", tool="list_dir", text=str(path))
        return {"ok": True, "path": str(path), "items": items}

    def _search(self, run: Run, args: dict) -> dict:
        pattern = str(args.get("pattern") or "").strip()
        if not pattern:
            return {"ok": False, "error": "pusty wzorzec"}
        path = self._resolve(args.get("path"))
        if path is None:
            return {"ok": False, "error": "ścieżka poza białą listą"}
        glob = str(args.get("glob") or "*")
        hits: list[str] = []
        try:
            for file in path.rglob(glob):
                if not file.is_file() or file.stat().st_size > 2_000_000:
                    continue
                if any(part in {".git", "build", "node_modules", ".gradle"} for part in file.parts):
                    continue
                try:
                    for n, line in enumerate(file.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                        if pattern.lower() in line.lower():
                            hits.append(f"{file}:{n}: {line.strip()[:160]}")
                            if len(hits) >= 60:
                                raise StopIteration
                except (OSError, UnicodeDecodeError):
                    continue
        except StopIteration:
            pass
        run.emit("tool", tool="search", text=f"{pattern} — {len(hits)} trafień")
        return {"ok": True, "hits": hits}

    def _glasses(self, run: Run, args: dict) -> dict:
        action = str(args.get("action") or "").strip()
        value = str(args.get("value") or "").strip()
        if action == "switch_desktop":
            desks = (self.get_state().get("desktops") or [])
            desk = next((d for d in desks if str(d.get("name", "")).lower() == value.lower()), None)
            desk = desk or next((d for d in desks if d.get("id") == value), None)
            if not desk:
                return {"ok": False, "error": "nie ma takiego pulpitu"}
            self.enqueue({"type": "apply_desktop", "desktopId": desk["id"]})
            run.emit("tool", tool="glasses", text=f"pulpit: {desk.get('name')}")
            return {"ok": True, "desktop": desk.get("name")}
        allowed = {"layout", "next_screen", "recenter", "fit_screens", "taskbar_toggle", "zoom_in", "zoom_out"}
        if action not in allowed:
            return {"ok": False, "error": f"nieznana akcja {action}"}
        cmd: dict[str, Any] = {"type": action}
        if action == "layout" and value:
            cmd["layout"] = value.upper()
        self.enqueue(cmd)
        run.emit("tool", tool="glasses", text=f"{action} {value}".strip())
        return {"ok": True, "action": action}
