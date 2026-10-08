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
import re
import socket
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

from agent import Agent
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
        # Desktop the phone applies by itself when the glasses connect ("" = off).
        "autostart_desktop": "",
        # Register every Windows machine in the tailnet that answers on 3389 as RDP.
        "auto_add_rdp": False,
    },
}

# Default port per computer kind — what the phone's client connects to, and what
# the hub probes to show the green dot.
KIND_PORTS = {"rdp": 3389, "moonlight": 47989, "vnc": 5900, "ssh": 22}
KIND_LABELS = {"rdp": "RDP", "moonlight": "Moonlight", "vnc": "VNC", "ssh": "SSH"}

SYSTEM_PROMPT = (
    "Jesteś asystentem GlassOS — pulpit w okularach VITURE, sterowany z komputera. "
    "Odpowiadaj po polsku, krótko i konkretnie. "
    "Możesz układać ekrany, pocztę, przeglądarkę, SSH i zdalne komputery (RDP / Moonlight / VNC). "
    "Gdy użytkownik chce zmianę pulpitu, na końcu odpowiedzi dodaj blok:\n"
    "```glassos\n"
    '{"layout":"TWO_SBS","screens":[[{"type":"remote","label":"Praca","hostId":"ID_KOMPUTERA"}],'
    '[{"type":"browser","label":"Web","url":"https://www.google.com"}]]}\n'
    "```\n"
    "Dozwolone type: mail, browser, ssh, remote (z hostId z listy komputerów), app. "
    "layout: FOCUS, SINGLE, TWO_SBS, THREE_SBS, SINGLE_WIDE."
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


APK_CACHE: dict = {"mtime": 0.0, "data": {}}


def apk_manifest_cached() -> dict:
    try:
        mtime = VERSION_FILE.stat().st_mtime if VERSION_FILE.exists() else 0.0
    except OSError:
        mtime = 0.0
    if mtime != APK_CACHE["mtime"] or not APK_CACHE["data"]:
        APK_CACHE["mtime"] = mtime
        APK_CACHE["data"] = apk_manifest()
    return APK_CACHE["data"]


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


REV = {"n": 1}


def bump_rev() -> int:
    """Every mutation bumps a counter so the browser can poll cheaply."""
    REV["n"] += 1
    return REV["n"]


def save_state(state: dict, persist: bool = True) -> None:
    """Bump the revision the web UI polls on and write to disk.

    The phone heartbeats every 2 s. Writing 35 kB of state.json — and bumping the
    revision, which makes every open browser re-download the full state — on each
    of those beats was pure churn. Heartbeats that carry nothing new pass
    ``persist=False`` and change neither the file nor the revision.
    """
    if not persist:
        return
    bump_rev()
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


STATE = load_state()
STARTED = time.time()
FRAMES: dict[str, deque[bytes]] = defaultdict(lambda: deque(maxlen=MAX_FRAMES))
PREVIEW_JPEG = bytearray()
PREVIEW_AT = 0.0

# Phone-side log lines (the app posts its own ring buffer) — this is how you see
# what GlassOS is doing without a USB cable and adb logcat.
LOGS: deque[dict] = deque(maxlen=400)
FILES_CACHE: dict = {"at": 0.0, "items": []}
PHONE_ONLINE_S = 8


def cached_files() -> list[dict]:
    """recent_files() touches the disk; the UI polls often, so cache it."""
    now = time.time()
    if now - FILES_CACHE["at"] > 20:
        try:
            FILES_CACHE["items"] = recent_files()
        except Exception:
            FILES_CACHE["items"] = []
        FILES_CACHE["at"] = now
    return FILES_CACHE["items"]


def phone_ago() -> float:
    return time.time() - float(STATE.get("phone", {}).get("seen", 0) or 0)


def phone_online() -> bool:
    return phone_ago() < PHONE_ONLINE_S


def usb_pid_suffix(diag: dict) -> str:
    try:
        pid = int(diag.get("usbPid") or 0)
    except (TypeError, ValueError):
        return ""
    return f" · pid 0x{pid:04x}" if pid else ""


def checks() -> list[dict]:
    """Every link in the chain, with the one action that fixes a broken one.

    This is the answer to "it connects but nothing works": each item says what
    is true right now, and what to do about it.
    """
    phone = STATE.get("phone", {}) or {}
    glasses = phone.get("glasses", {}) or {}
    diag = phone.get("diag", {}) or {}
    out: list[dict] = []

    def add(key, label, ok, detail="", hint=""):
        out.append({"key": key, "label": label, "ok": bool(ok), "detail": detail, "hint": hint})

    online = phone_online()
    ago = phone_ago()
    add(
        "phone",
        "Telefon",
        online,
        (f"{diag.get('device') or 'telefon'} · GlassOS {diag.get('appVersion') or '?'}"
         if online else (f"cisza od {int(ago)} s" if ago < 10**6 else "nigdy się nie zgłosił")),
        "Otwórz GlassOS na telefonie i sprawdź, czy ma zasięg do tego huba (Tailscale / to samo Wi-Fi).",
    )
    usb = bool(glasses.get("usb", glasses.get("connected")))
    add(
        "usb",
        "Okulary na USB",
        usb,
        (glasses.get("model") or "—") + usb_pid_suffix(diag),
        "Wepnij VITURE w USB-C telefonu i zezwól na dostęp do urządzenia USB.",
    )
    disp = bool(glasses.get("display", diag.get("glassesDisplay")))
    add(
        "display",
        "Ekran okularów",
        disp,
        diag.get("displayLabel") or (glasses.get("mode") or "—"),
        "Telefon musi wystawiać obraz przez DisplayPort. Na Samsungu wyłącz DeX / dublowanie ekranu "
        "— GlassOS potrzebuje okularów jako osobnego ekranu.",
    )
    ws = bool(glasses.get("workspace"))
    add(
        "workspace",
        "Pulpit w okularach",
        ws,
        diag.get("workspaceError") or ("działa" if ws else "nie wystartował"),
        "Otwórz GlassOS na pierwszym planie przy podłączonych okularach.",
    )
    dof = bool(glasses.get("dof"))
    add(
        "tracking",
        "Śledzenie głowy",
        dof,
        diag.get("trackingDetail") or (glasses.get("tracking") or ("3DoF" if dof else "brak")),
        "Zezwól GlassOS na dostęp do urządzenia USB VITURE (okno systemowe przy podłączeniu).",
    )
    sdk = bool(diag.get("sdkPresent"))
    add(
        "sdk",
        "SDK VITURE",
        sdk,
        diag.get("sdkVersion") or ("wkompilowane" if sdk else "brak natywnego mostka"),
        "Zbuduj APK z wgranym SDK (sdk/VITURE_XR_Glasses_SDK_for_Android) i zainstaluj nową wersję.",
    )
    adb = bool(diag.get("privileged"))
    add(
        "adb",
        "Helper ADB (apki z telefonu)",
        adb,
        diag.get("privilegedState") or ("gotowy" if adb else "nieaktywny"),
        "Opcjonalne. Potrzebne tylko po to, by odpalać apki telefonu na ekranach okularów: "
        "sparuj debugowanie bezprzewodowe albo uruchom tools/adb-helper.ps1 z PC.",
    )
    ts_ok = bool(TS.get("ok")) and bool((TS.get("self") or {}).get("online", True))
    peers = TS.get("peers") or []
    add(
        "tailscale",
        "Tailscale na serwerze",
        ts_ok,
        (f"{(TS.get('self') or {}).get('dns') or (TS.get('self') or {}).get('name')} · "
         f"{sum(1 for p in peers if p.get('online'))}/{len(peers)} urządzeń online") if ts_ok
        else (TS.get("error") or "—"),
        "Zainstaluj Tailscale na serwerze huba i zaloguj (tailscale up) — hub sam wykryje komputery w tailnecie.",
    )
    ai_ok = bool(deepseek_key() or xai_key())
    add(
        "ai",
        "Asystent AI",
        ai_ok,
        (os.environ.get("DEEPSEEK_MODEL") or "deepseek-flash") if deepseek_key() else
        (xai_model() if xai_key() else "brak klucza"),
        "Wstaw DEEPSEEK_API_KEY do tools/hub/.env i zrestartuj hub.",
    )
    return out


def status_payload() -> dict:
    phone = STATE.get("phone", {}) or {}
    diag = phone.get("diag", {}) or {}
    items = checks()
    return {
        "ok": True,
        "rev": REV["n"],
        "uptime": int(time.time() - STARTED),
        "phone": {
            "online": phone_online(),
            "ago": int(phone_ago()) if phone.get("seen") else None,
            "apps": len(phone.get("apps") or []),
            "version": diag.get("appVersion") or phone.get("version") or "",
            "device": diag.get("device") or "",
        },
        "glasses": phone.get("glasses", {}) or {},
        "checks": items,
        "problems": [c for c in items if not c["ok"]],
        "preview": {
            "present": bool(PREVIEW_JPEG),
            "age": round(time.time() - PREVIEW_AT, 1) if PREVIEW_AT else None,
        },
        "pending": len(STATE.get("pending") or []),
        "logs": len(LOGS),
        "computers": [
            {"id": c["id"], "online": c["online"], "ms": c["ms"]} for c in computers_with_status()
        ],
        "settings": STATE.get("settings") or {},
        "tailscale": {
            "ok": TS.get("ok"),
            "error": TS.get("error"),
            "self": TS.get("self"),
            "at": TS.get("at"),
            "discovered": discovered_peers(),
        },
        "ai": {
            "deepseek": bool(deepseek_key()),
            "xai": bool(xai_key()),
            "model": (os.environ.get("DEEPSEEK_MODEL") or "deepseek-flash")
            if deepseek_key()
            else (xai_model() if xai_key() else ""),
        },
        "apk": apk_manifest_cached(),
    }


ASSISTANT: Assistant | None = None
AGENT: Agent | None = None


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


def norm_computer(body: dict, existing: dict | None = None) -> dict:
    """One shape for a computer record, whatever the browser sent.

    `host` is what the *phone* connects to — a Tailscale MagicDNS name is the
    stable choice. `mac` enables Wake-on-LAN from the hub (same LAN only).
    """
    base = dict(existing or {})
    kind = str(body.get("kind") or base.get("kind") or "ssh").lower()
    if kind not in KIND_PORTS:
        kind = "ssh"
    try:
        port = int(body.get("port") or 0)
    except (TypeError, ValueError):
        port = 0
    if port <= 0:
        port = int(base.get("port") or 0) if kind == base.get("kind") else 0
    if port <= 0:
        port = KIND_PORTS[kind]

    def num(key: str) -> int:
        try:
            return int(body.get(key) or base.get(key) or 0)
        except (TypeError, ValueError):
            return 0

    host = str(body.get("host") or base.get("host") or "").strip()
    return {
        "id": base.get("id") or uuid.uuid4().hex[:8],
        "name": str(body.get("name") or base.get("name") or host or "Komputer").strip(),
        "host": host,
        "user": str(body.get("user") if body.get("user") is not None else base.get("user") or "").strip(),
        "port": port,
        "kind": kind,
        "mac": str(body.get("mac") if body.get("mac") is not None else base.get("mac") or "").strip(),
        "uuid": str(body.get("uuid") if body.get("uuid") is not None else base.get("uuid") or "").strip(),
        "app": str(body.get("app") if body.get("app") is not None else base.get("app") or "").strip(),
        "appId": str(body.get("appId") if body.get("appId") is not None else base.get("appId") or "").strip(),
        "width": num("width"),
        "height": num("height"),
    }


# ---- reachability: a green dot per computer, probed from the hub every few seconds ----

PROBE: dict[str, dict] = {}
PROBE_INTERVAL_S = 8.0


def probe_port(host: str, port: int, timeout: float = 1.5) -> float | None:
    """Round-trip ms of a TCP connect to host:port, or None when unreachable."""
    if not host:
        return None
    started = time.time()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return round((time.time() - started) * 1000)
    except OSError:
        return None


def probe_loop() -> None:
    while True:
        with LOCK:
            comps = [dict(c) for c in (STATE.get("computers") or [])]
        for c in comps:
            ms = probe_port(c.get("host") or "", int(c.get("port") or KIND_PORTS.get(c.get("kind"), 22)))
            entry = PROBE.setdefault(c["id"], {"online": False, "ms": None, "seen": 0.0})
            entry["online"] = ms is not None
            entry["ms"] = ms
            if ms is not None:
                entry["seen"] = time.time()
        time.sleep(PROBE_INTERVAL_S)


# ---- Tailscale: who is in the tailnet, straight from the local CLI (no API key) ----

TS: dict = {"ok": False, "error": "nie sprawdzono", "self": {}, "peers": [], "at": 0.0}
TS_INTERVAL_S = 20.0
TS_CANDIDATES = [
    "tailscale",
    r"C:\Program Files\Tailscale\tailscale.exe",
    "/usr/bin/tailscale",
    "/usr/local/bin/tailscale",
    "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
]


def tailscale_bin() -> str | None:
    import shutil
    for cand in TS_CANDIDATES:
        found = shutil.which(cand) if not os.path.isabs(cand) else (cand if os.path.exists(cand) else None)
        if found:
            return found
    return None


def host_matches(comp: dict, peer: dict) -> bool:
    """Is this registered computer the same machine as this tailnet peer?"""
    host = (comp.get("host") or "").strip().lower().rstrip(".")
    if not host:
        return False
    names = {
        (peer.get("dns") or "").lower(),
        (peer.get("dns") or "").lower().split(".")[0],
        (peer.get("name") or "").lower(),
        (peer.get("ip") or "").lower(),
    }
    return host in names or host.split(".")[0] in names


def refresh_tailscale() -> None:
    """`tailscale status --json` → TS. Peers get a suggested kind from a quick port probe."""
    binary = tailscale_bin()
    if not binary:
        TS.update({"ok": False, "error": "brak tailscale CLI na serwerze", "at": time.time()})
        return
    try:
        proc = subprocess.run([binary, "status", "--json"], capture_output=True, text=True, timeout=15)
        if proc.returncode != 0:
            TS.update({"ok": False, "error": (proc.stderr or proc.stdout).strip()[:200] or "tailscale status ✕", "at": time.time()})
            return
        data = json.loads(proc.stdout or "{}")
    except Exception as exc:  # noqa: BLE001
        TS.update({"ok": False, "error": str(exc)[:200], "at": time.time()})
        return
    me = data.get("Self") or {}
    peers = []
    for raw in (data.get("Peer") or {}).values():
        dns = (raw.get("DNSName") or "").rstrip(".")
        ips = raw.get("TailscaleIPs") or []
        peers.append({
            "name": raw.get("HostName") or dns.split(".")[0],
            "dns": dns,
            "ip": next((ip for ip in ips if "." in ip), ips[0] if ips else ""),
            "os": (raw.get("OS") or "").lower(),
            "online": bool(raw.get("Online")),
            "ports": {},
            "suggest": "",
        })
    # Which services answer — only for peers that are online, in parallel, short timeout.
    def probe_peer(peer: dict) -> None:
        host = peer["dns"] or peer["ip"]
        for kind, port in KIND_PORTS.items():
            peer["ports"][kind] = probe_port(host, port, timeout=1.2) is not None
        # Windows: RDP first. Anything else: an open 3389 is rarer than ssh, so
        # prefer the streaming / VNC / shell ports before guessing xrdp.
        order = ["rdp", "moonlight", "vnc", "ssh"] if peer["os"] == "windows" else ["moonlight", "vnc", "ssh", "rdp"]
        peer["suggest"] = next((k for k in order if peer["ports"].get(k)), "")
    threads = [threading.Thread(target=probe_peer, args=(p,), daemon=True) for p in peers if p["online"]]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=6)
    TS.update({
        "ok": True,
        "error": "",
        "self": {
            "name": me.get("HostName") or "",
            "dns": (me.get("DNSName") or "").rstrip("."),
            "ip": next((ip for ip in (me.get("TailscaleIPs") or []) if "." in ip), ""),
            "online": bool(me.get("Online")),
        },
        "peers": peers,
        "at": time.time(),
    })
    auto_register_rdp()


def auto_register_rdp() -> None:
    """With `auto_add_rdp` on, every Windows peer answering on 3389 becomes an RDP computer."""
    with LOCK:
        if not (STATE.get("settings") or {}).get("auto_add_rdp"):
            return
        comps = STATE.setdefault("computers", [])
        added = 0
        for peer in TS.get("peers") or []:
            if not (peer.get("online") and peer.get("ports", {}).get("rdp")):
                continue
            if any(host_matches(c, peer) for c in comps):
                continue
            comps.append(norm_computer({"name": peer["name"], "host": peer["dns"] or peer["ip"], "kind": "rdp"}))
            added += 1
        if added:
            save_state(STATE)


def discovered_peers() -> list[dict]:
    """Tailnet peers annotated with whether they are already on the computer list."""
    out = []
    comps = STATE.get("computers") or []
    for peer in TS.get("peers") or []:
        d = dict(peer)
        match = next((c for c in comps if host_matches(c, peer)), None)
        d["registered"] = match["id"] if match else ""
        # The phone and the hub itself are not things you remote into.
        d["skip"] = d["os"] in ("android", "ios") or (TS.get("self") or {}).get("dns") == d["dns"]
        out.append(d)
    out.sort(key=lambda d: (d["skip"], not d["online"], d["name"].lower()))
    return out


def tailscale_loop() -> None:
    while True:
        try:
            refresh_tailscale()
        except Exception as exc:  # noqa: BLE001
            TS.update({"ok": False, "error": str(exc)[:200], "at": time.time()})
        time.sleep(TS_INTERVAL_S)


def computers_with_status() -> list[dict]:
    out = []
    for c in STATE.get("computers") or []:
        pr = PROBE.get(c.get("id") or "", {})
        d = dict(c)
        d["online"] = bool(pr.get("online"))
        d["ms"] = pr.get("ms")
        d["kindLabel"] = KIND_LABELS.get(c.get("kind") or "", c.get("kind") or "")
        out.append(d)
    return out


def send_wol(mac: str, host: str = "") -> dict:
    """Wake-on-LAN magic packet: LAN broadcast plus, if it resolves, the host itself.

    Only works when the hub sits in the same LAN as the machine (or the router
    forwards directed broadcasts) — Tailscale does not carry broadcast frames.
    """
    clean = re.sub(r"[^0-9a-fA-F]", "", mac or "")
    if len(clean) != 12:
        return {"ok": False, "error": "MAC musi mieć 12 znaków hex, np. AA:BB:CC:DD:EE:FF"}
    payload = b"\xff" * 6 + bytes.fromhex(clean) * 16
    sent = []
    targets = [("255.255.255.255", 9), ("255.255.255.255", 7)]
    if host:
        try:
            targets.append((socket.gethostbyname(host), 9))
        except OSError:
            pass
    for addr, port in targets:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.sendto(payload, (addr, port))
                sent.append(f"{addr}:{port}")
        except OSError as exc:
            sent.append(f"{addr}:{port} ✕ {exc}")
    return {"ok": True, "sent": sent}


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
        if path == "/api/computers":
            with LOCK:
                self._json(200, {"computers": computers_with_status()})
            return
        if path == "/api/tailscale":
            with LOCK:
                self._json(200, {"ok": TS.get("ok"), "error": TS.get("error"), "self": TS.get("self"),
                                 "at": TS.get("at"), "discovered": discovered_peers()})
            return
        if path == "/api/state":
            with LOCK:
                payload = dict(STATE)
                payload["computers"] = computers_with_status()
                payload["rev"] = REV["n"]
                payload["files"] = cached_files()
                payload["ai"] = {
                    "deepseek": bool(deepseek_key()),
                    "model": os.environ.get("DEEPSEEK_MODEL") or "deepseek-flash",
                }
                self._json(200, payload)
            return
        if path == "/api/status":
            # Small and cheap: this is what the browser polls every second.
            with LOCK:
                self._json(200, status_payload())
            return
        if path == "/api/checks":
            with LOCK:
                self._json(200, {"checks": checks()})
            return
        if path == "/api/diag":
            with LOCK:
                self._json(
                    200,
                    {
                        "diag": (STATE.get("phone", {}) or {}).get("diag", {}),
                        "glasses": (STATE.get("phone", {}) or {}).get("glasses", {}),
                        "checks": checks(),
                        "logs": list(LOGS)[-120:],
                    },
                )
            return
        if path == "/api/agent/stream":
            self._agent_stream(urlparse(self.path).query)
            return
        if path == "/api/agent/runs":
            runs = []
            if AGENT is not None:
                for run in list(AGENT.runs.values())[-6:]:
                    runs.append(
                        {
                            "id": run.id,
                            "prompt": run.prompt,
                            "done": run.done,
                            "pending": run.pending,
                            "started": run.started,
                            "events": len(run.history),
                        }
                    )
            self._json(200, {"runs": runs, "roots": [str(r) for r in __import__("agent").roots()]})
            return
        if path == "/api/logs":
            with LOCK:
                self._json(200, {"logs": list(LOGS)})
            return
        if path == "/api/apps":
            with LOCK:
                self._json(200, {"apps": (STATE.get("phone", {}) or {}).get("apps") or []})
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
                # 204, not 404: the <img> in the UI polls this and a 404 storm in
                # the console is noise, not information.
                self.send_response(204)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return
        asset = self._static_asset(path)
        if asset is not None:
            body, ctype = asset
            self._bytes(200, body, ctype)
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

    def _agent_stream(self, query: str) -> None:
        """Server-sent events for one agent run — the glasses page listens on this."""
        params = urllib.parse.parse_qs(query or "")
        run_id = (params.get("id") or [""])[0]
        run = AGENT.get(run_id) if AGENT else None
        if run is None:
            self._json(404, {"error": "nieznany przebieg"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.close_connection = True

        def write(event: dict) -> bool:
            try:
                self.wfile.write(
                    ("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode("utf-8")
                )
                self.wfile.flush()
                return True
            except (BrokenPipeError, ConnectionResetError, OSError):
                return False

        # Odtwarzamy historię i dalej idziemy po indeksie, a nie po kolejce:
        # kolejkę dzieliłoby między sobą kilka otwartych kart i każda widziałaby
        # połowę zdarzeń.
        idx = 0
        quiet = 0.0
        while True:
            if idx < len(run.history):
                event = run.history[idx]
                idx += 1
                quiet = 0.0
                if not write(event):
                    return
                if event.get("kind") == "done":
                    return
                continue
            if run.done:
                return
            time.sleep(0.15)
            quiet += 0.15
            if quiet >= 15:
                quiet = 0.0
                if not write({"kind": "ping", "ts": time.time()}):
                    return

    STATIC_TYPES = {
        ".js": "application/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".html": "text/html; charset=utf-8",
        ".json": "application/json; charset=utf-8",
        ".svg": "image/svg+xml",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".ico": "image/x-icon",
        ".webmanifest": "application/manifest+json",
    }

    def _static_asset(self, path: str) -> tuple[bytes, str] | None:
        """Serve tools/hub/static/* so the UI can live in real .js / .css files."""
        name = path.lstrip("/")
        if not name or ".." in name:
            return None
        target = (STATIC / name).resolve()
        try:
            target.relative_to(STATIC.resolve())
        except ValueError:
            return None
        if not target.is_file():
            return None
        ctype = self.STATIC_TYPES.get(target.suffix.lower())
        if ctype is None:
            return None
        return target.read_bytes(), ctype

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
                prev = STATE.get("phone", {}) or {}
                apps = body.get("apps")
                # A heartbeat without an app list must not wipe the list we have.
                if not apps:
                    apps = prev.get("apps") or []
                glasses = body.get("glasses") or {}
                diag = body.get("diag") or prev.get("diag") or {}
                changed = (
                    len(apps) != len(prev.get("apps") or [])
                    or glasses != (prev.get("glasses") or {})
                    or diag != (prev.get("diag") or {})
                )
                STATE["phone"] = {
                    "apps": apps,
                    "glasses": glasses,
                    "diag": diag,
                    "version": body.get("version") or prev.get("version") or "",
                    "seen": time.time(),
                }
                for line in (body.get("logs") or [])[-60:]:
                    LOGS.append(
                        {
                            "ts": time.time(),
                            "level": str(line.get("level") or "I")[:1],
                            "tag": str(line.get("tag") or "")[:40],
                            "text": str(line.get("text") or "")[:400],
                        }
                        if isinstance(line, dict)
                        else {"ts": time.time(), "level": "I", "tag": "", "text": str(line)[:400]}
                    )
                # Persist only when something real moved — see save_state().
                save_state(STATE, persist=changed)
            self._json(
                200,
                {
                    "ok": True,
                    "pending": len(STATE.get("pending") or []),
                    "wantApps": not (STATE.get("phone", {}) or {}).get("apps"),
                },
            )
            return
        if path == "/v1/phone/log":
            with LOCK:
                for line in (body.get("logs") or [])[-200:]:
                    if isinstance(line, dict):
                        LOGS.append(
                            {
                                "ts": float(line.get("ts") or time.time()),
                                "level": str(line.get("level") or "I")[:1],
                                "tag": str(line.get("tag") or "")[:40],
                                "text": str(line.get("text") or "")[:400],
                            }
                        )
                    else:
                        LOGS.append({"ts": time.time(), "level": "I", "tag": "", "text": str(line)[:400]})
            self._json(200, {"ok": True, "stored": len(LOGS)})
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
        if path.startswith("/api/desktops/") and path.endswith("/delete"):
            desk_id = path.split("/")[3]
            with LOCK:
                desks = STATE.setdefault("desktops", [])
                before = len(desks)
                STATE["desktops"] = [d for d in desks if d.get("id") != desk_id]
                save_state(STATE)
            self._json(200, {"ok": True, "removed": before - len(STATE["desktops"])})
            return
        if path == "/api/agent/ask":
            text = str(body.get("text") or "").strip()
            if not text:
                self._json(400, {"error": "puste zadanie"})
                return
            if AGENT is None:
                self._json(503, {"error": "agent nie wystartował"})
                return
            run = AGENT.start(text)
            self._json(200, {"ok": True, "id": run.id})
            return
        if path == "/api/agent/approve":
            if AGENT is None:
                self._json(503, {"error": "agent nie wystartował"})
                return
            ok = AGENT.approve(str(body.get("id") or ""), bool(body.get("ok")))
            self._json(200, {"ok": ok})
            return
        if path == "/api/chat/clear":
            with LOCK:
                STATE["chat"] = []
                save_state(STATE)
            self._json(200, {"ok": True})
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
            comp = norm_computer(body)
            if not comp["host"]:
                self._json(400, {"error": "podaj host (nazwa w Tailscale albo IP)"})
                return
            with LOCK:
                STATE.setdefault("computers", []).append(comp)
                save_state(STATE)
            self._json(200, comp)
            return
        if path == "/api/tailscale/refresh":
            threading.Thread(target=refresh_tailscale, daemon=True).start()
            self._json(200, {"ok": True})
            return
        if path == "/api/tailscale/add":
            # Register a discovered tailnet peer as a computer, with the probed kind
            # unless the browser picked one.
            want = str(body.get("dns") or body.get("name") or "").lower()
            peer = next((p for p in (TS.get("peers") or []) if want in ((p.get("dns") or "").lower(), (p.get("name") or "").lower())), None)
            if not peer:
                self._json(404, {"error": "nie ma takiego urządzenia w tailnecie"})
                return
            kind = str(body.get("kind") or peer.get("suggest") or ("rdp" if peer.get("os") == "windows" else "ssh"))
            comp = norm_computer({
                "name": body.get("label") or peer["name"],
                "host": peer["dns"] or peer["ip"],
                "kind": kind,
                "user": body.get("user") or "",
            })
            with LOCK:
                comps = STATE.setdefault("computers", [])
                existing = next((c for c in comps if host_matches(c, peer)), None)
                if existing:
                    self._json(200, existing)
                    return
                comps.append(comp)
                save_state(STATE)
            self._json(200, comp)
            return
        if path.startswith("/api/computers/") and path.endswith("/delete"):
            cid = path.split("/")[3]
            with LOCK:
                comps = STATE.setdefault("computers", [])
                before = len(comps)
                STATE["computers"] = [c for c in comps if c.get("id") != cid]
                # Tiles pointing at the removed machine would launch nothing — drop them.
                for desk in STATE.get("desktops") or []:
                    desk["screens"] = [
                        [a for a in screen if not (a.get("type") == "remote" and a.get("hostId") == cid)]
                        for screen in (desk.get("screens") or [])
                    ]
                PROBE.pop(cid, None)
                save_state(STATE)
            self._json(200, {"ok": True, "removed": before - len(STATE["computers"])})
            return
        if path.startswith("/api/computers/") and path.endswith("/wake"):
            cid = path.split("/")[3]
            comp = computer_by_id(cid)
            if not comp:
                self._json(404, {"error": "brak komputera"})
                return
            self._json(200, send_wol(comp.get("mac") or "", comp.get("host") or ""))
            return
        if path.startswith("/api/computers/") and path.endswith("/open"):
            # One computer onto one glasses screen, without touching the saved desktop.
            cid = path.split("/")[3]
            comp = computer_by_id(cid)
            if not comp:
                self._json(404, {"error": "brak komputera"})
                return
            try:
                screen = int(body.get("screenIdx") or 0)
            except (TypeError, ValueError):
                screen = 0
            cmd = enqueue({"type": "remote", "hostId": cid, "screenIdx": screen, "label": comp.get("name")})
            self._json(200, cmd)
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
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        if path.startswith("/api/computers/"):
            cid = path.rsplit("/", 1)[-1]
            with LOCK:
                comps = STATE.setdefault("computers", [])
                for i, c in enumerate(comps):
                    if c.get("id") == cid:
                        comps[i] = norm_computer(body, c)
                        save_state(STATE)
                        self._json(200, comps[i])
                        return
            self._json(404, {"error": "computer not found"})
            return
        if not path.startswith("/api/desktops/"):
            self._json(404, {"error": "not found"})
            return
        desk_id = path.rsplit("/", 1)[-1]
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
    global ASSISTANT, AGENT
    AGENT = Agent(enqueue=enqueue, get_state=lambda: STATE)
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
    threading.Thread(target=probe_loop, name="probe", daemon=True).start()
    threading.Thread(target=tailscale_loop, name="tailscale", daemon=True).start()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
