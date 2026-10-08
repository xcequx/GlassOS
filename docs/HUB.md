# Pulpit z komputera — GlassOS Hub

To jest główny sposób pracy: **układasz ekrany i aplikacje w przeglądarce**, telefon wdraża to w okularach.

```
Przeglądarka na PC / serwerze
        │  http://IP:30100
        ▼
   GlassOS Hub
        │  Wi-Fi / Tailscale
        ▼
   Telefon + Luma Pro
        │
        ▼
   Okulary: ekran 1 / 2 / 3 z Twoimi apkami
```

Hub możesz trzymać na laptopie albo na dowolnym serwerze w LAN (Raspberry Pi, NAS, VPS w sieci domowej). Telefon musi dosięgnąć ten adres.

## Start na komputerze

```powershell
cd C:\Users\User\Desktop\Glasses\tools\hub
python server.py
```

Otwórz w Chrome: **http://IP_KOMPUTERA:30100**

Zapora (admin):

```powershell
netsh advfirewall firewall add rule name="GlassOS Hub" dir=in action=allow protocol=tcp localport=30100
```

Zdalnie (poza LAN) najprościej przez Tailscale:

```powershell
tailscale serve --bg 30100
```

Opcjonalnie AI: skopiuj `tools/ai-gateway/.env.example` do `tools/hub/.env` i wstaw
`DEEPSEEK_API_KEY` (albo `XAI_API_KEY`).

## Co widać na stronie

| Sekcja | Do czego służy |
|---|---|
| **Pasek u góry** | telefon / USB / ekran / pulpit / głowa / ADB — zielone = działa |
| **Stan połączenia** | ta sama lista, ale z powodem i konkretną podpowiedzią przy każdym ✕ |
| **Podgląd okularów** | klatki z telefonu; „brak obrazu" = pulpit w okularach nie działa |
| **Układ i ekrany** | wybór układu, przeciąganie apek na Ekran 1/2/3, **Włącz na okularach** |
| **Okulary — sterowanie** | jasność, 2D/3D, przyciemnienie, naprawa trackingu, restart pulpitu |
| **Asystent** | DeepSeek z narzędziami — potrafi sam przełączyć pulpit i zapamiętać fakty |
| **Log telefonu** | ostatnie zdarzenia z aplikacji — zdalny odpowiednik `adb logcat` |
| **Surowa diagnostyka** | pełny zrzut z telefonu: ekrany, USB, SDK, ADB |

Strona sama mówi, kiedy traci hub — czerwony pasek u góry zamiast cichego zamarcia.

## Jak układać

1. Z lewej wybierz pulpit albo **Nowy pulpit**.
2. Układ: 1 / 2 / 3 ekrany, Focus, Kino.
3. Z prawej przeciągnij aplikacje z telefonu na **Ekran 1 / 2 / 3**, albo dodaj
   **+ Komputer / + Poczta / + Strona / + SSH**. Adres strony wpisujesz wprost w kaflu.
   Komputer na ekranie = pełny monitor 1080p (RDP / Moonlight / VNC) — szczegóły i
   instalacja huba na serwerze: **[REMOTE.md](REMOTE.md)**.
4. **Włącz na okularach** (zapisuje i wysyła).

Telefon odbiera polecenie przy najbliższym pulsie, czyli do 2 sekund.

## Kiedy nic nie działa

Patrz na **Stan połączenia** — kolejność ma znaczenie, każdy kolejny punkt zależy od poprzedniego:

1. **Telefon** ✕ → GlassOS nie działa albo nie ma łączności z hubem. Otwórz aplikację; przy
   Tailscale sprawdź, czy telefon jest w tej samej sieci tailnet.
2. **Okulary na USB** ✕ → telefon nie widzi VITURE. Kabel, przejściówka, zgoda na USB.
3. **Ekran okularów** ✕ → nie ma obrazu z DisplayPort. Na Samsungu wyłącz DeX / dublowanie —
   GlassOS potrzebuje okularów jako *osobnego* ekranu, nie kopii telefonu.
4. **Pulpit** ✕ → ekran jest, ale Presentation nie wstało. GlassOS musi być na wierzchu przy
   podłączaniu; przycisk **Restart pulpitu** robi to zdalnie.
5. **Głowa** ✕ → brak IMU. Zwykle brak zgody na USB albo APK bez SDK (patrz punkt SDK).
6. **ADB** ✕ → opcjonalne. Bez tego działa wszystko poza odpalaniem apek telefonu na ekranach.

## Serwer 24/7

```bash
cd tools/hub
python3 server.py --host 0.0.0.0 --port 30100
```

Zwykły HTTP na 30100, bez bazy. Stan w `tools/hub/data/state.json`.

## API (gdy chcesz skryptować)

| Metoda | Ścieżka | Do czego |
|---|---|---|
| GET | `/api/status` | lekki status + checklista (to odpytuje przeglądarka) |
| GET | `/api/state` | pełny stan: pulpity, komputery, apki telefonu, czat |
| GET | `/api/diag` · `/api/logs` | diagnostyka i log z telefonu |
| POST | `/api/apply` | `{"id":"praca"}` — włącz pulpit |
| GET/POST | `/api/tailscale`, `/api/tailscale/add` | urządzenia tailnetu z `tailscale status` na serwerze; dodanie wykrytego komputera |
| GET/POST/PUT | `/api/computers`, `/api/computers/<id>` | komputery (RDP / Moonlight / VNC / SSH), `online` z sondy portu |
| POST | `/api/computers/<id>/open` · `/wake` · `/delete` | komputer na ekran teraz · Wake-on-LAN · usuń |
| POST | `/api/settings` | `{"autostart_desktop":"praca"}` — pulpit wchodzi sam po podłączeniu okularów |
| POST | `/api/command` | `{"type":"recenter"}`, `zoom_in`, `next_screen`, `brightness_up`, `stereo_toggle`, `restart_workspace`, `retry_tracking`, `remote` (`hostId`, `screenIdx`), `open_url` (`url`, `screenIdx`) |
| POST | `/api/ai/chat` | `{"text":"daj 2 ekrany…"}` |
| POST | `/v1/phone/hello` | puls telefonu (apki, stan okularów, diagnostyka, log) |
