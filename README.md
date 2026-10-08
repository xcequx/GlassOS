# GlassOS

Przestrzenny pulpit do pracy w okularach **VITURE Luma / Luma Pro**.

Telefon jest trackpadem, launcherem i centrum sterowania. Okulary są osobnym ekranem USB-C (nie klonem telefonu) z wieloma wirtualnymi monitorami, kursorem z głowy i native 3DoF.

To fork [darkclad/uxspace](https://github.com/darkclad/uxspace) (Apache-2.0) z własnym UI GlassOS, oficjalnym mostkiem VITURE SDK i warstwą sterowania sprzętem.

```
Telefon (GlassOS)
├── Touchpad
├── Launcher
├── Workspace Manager
└── Quick controls (jasność / 2D-3D / recenter / head cursor)
         │
         ├── DisplayManager + Presentation   → obraz na okularach
         └── VITURE SDK / JNI                → IMU, 3DoF, jasność, film
                 │
                 ▼
         VITURE Luma — spatial desktop + head cursor
```

## Co działa w v1

| Funkcja | Opis |
|---|---|
| **Glasses Dashboard** | Wykrycie Luma, model, rozdzielczość, Hz, tracking, SDK |
| **Phone Touchpad** | Tap, przeciąganie, prawy klik (long press), scroll dwoma palcami, pinch-zoom |
| **Head Cursor** | Patrzysz — kursor jedzie z głową; klikasz telefonem |
| **Workspace** | Focus, 1 ekran, 2 ekrany, 3 ekrany, kino |
| **Recenter** | Aktualny kierunek głowy = środek pulpitu |
| **Quick launcher** | Przeglądarka, Gmail, ChatGPT, Slack/Teams, YouTube, terminal/RDP + pełna lista aplikacji |
| **Zdalne komputery** | RDP / Moonlight / VNC / SSH jako pełne monitory 1080p, prawdziwa mysz, autostart profilu ([docs/REMOTE.md](docs/REMOTE.md)) |
| **Sterowanie Luma** | Jasność, przyciemnienie soczewek, 2D/3D |

Aplikacje trzecie startują na zaufanych VirtualDisplay przez helper ADB (Wireless Debugging, bez roota i bez osobnego Shizuku).

## Sprzęt

| | Luma | Luma Pro |
|---|---|---|
| Obraz USB-C DP | tak | tak |
| IMU / 3DoF / head cursor | tak | tak |
| Kamera pass-through | nie | tak (UVC 1080p30) — zaplanowane na v2 |

Oficjalnie Luma i Luma Pro to **3DoF**. 6DoF zostawiamy na Luma Ultra / późniejszy test na Twoim firmware.

Telefon: Android 11+, USB-C DisplayPort Alt Mode, arm64. Najlepiej Pixel / Samsung z One UI.

## Pulpit z komputera (to, na czym pracujesz)

Hub: **[docs/HUB.md](docs/HUB.md)**.

```powershell
python C:\Users\User\Desktop\Glasses\tools\hub\server.py
```

W przeglądarce `http://IP_PC:30100` przeciągasz aplikacje na ekrany 1/2/3 i klikasz **Włącz na okularach**. Telefon (GlassOS) wdraża layout i odpala apki.

## Zdalne komputery — monitory w okularach

Hub na serwerze w Tailscale, komputery jako pełne monitory 1080p (RDP / Moonlight / VNC / SSH),
mysz i klawiatura Bluetooth jak przy biurku, profil wchodzi sam po podłączeniu okularów.
Instalacja huba na serwerze, konfiguracja komputerów i telefonu: **[docs/REMOTE.md](docs/REMOTE.md)**.

```bash
# serwer Linux w tailnecie
git clone https://github.com/xcequx/GlassOS.git ~/glassos && sudo bash ~/glassos/tools/hub/deploy/install-linux.sh
```

## Uruchomienie i test (telefon + komputer)

Pełna instrukcja kamery/AI: **[docs/START.md](docs/START.md)**.

Skrót:

1. Na PC: `python tools/ai-gateway/server.py` (klucz `XAI_API_KEY` w `tools/ai-gateway/.env`).
2. W Android Studio wgraj GlassOS na telefon.
3. Sparuj Wireless Debugging, podłącz Luma Pro USB-C.
4. Zakładka **AI** → źródło **Luma Pro** → Gateway `http://IP_KOMPUTERA:30100` → **Test PC** → **Co widzę**.

Kamera Luma Pro to UVC (`0x0C45:0x636B`). Jeśli telefon nie wystawi jej jako Camera2, pipeline AI działa też z kamerą telefonu — ten sam gateway.

## Budowa

Wymagania: JDK 17+, Android SDK (API 36). Do trackingu dodatkowo NDK `30.0.14904198`
i CMake `4.1.2`.

```powershell
# local.properties — ścieżka do Android SDK
sdk.dir=C:\Users\USER\AppData\Local\Android\Sdk

.\gradlew.bat :app:assembleDebug
```

Oficjalne SDK VITURE wrzuć do `sdk/VITURE_XR_Glasses_SDK_for_Android/` — build sam je
skopiuje w odpowiednie miejsca i **sam włączy** natywny mostek, gdy znajdzie SDK i NDK:

```
GlassOS glasses: native bridge ON (SDK=true, NDK=true)
```

Bez SDK albo bez NDK projekt i tak się buduje (`... OFF`) — pulpit i touchpad działają,
tracking i pokrętła sprzętowe nie. Szczegóły: [`glasses/VENDOR_SDK.md`](glasses/VENDOR_SDK.md).

Publikacja na hub (telefon aktualizuje się sam z `/api/version`):

```powershell
powershell -ExecutionPolicy Bypass -File tools\hub\publish-apk.ps1
```

## Agent roboczy

Agent z narzędziami (powłoka, pliki, sterowanie pulpitem) na DeepSeeku, ze strumieniem
kroków na ekranie w okularach i głosem z przeglądarki: **[docs/AGENT.md](docs/AGENT.md)**.
Strona `/agent.html` na hubie, gotowy pulpit **Agent** do włączenia na okularach.

Polecenia zmieniające cokolwiek czekają na Twoją zgodę, praca tylko w katalogach z białej
listy. Inspiracja: [claude-code-g2](https://github.com/sam-siavoshian/claude-code-g2).

## Kiedy coś nie działa

Telefon co 2 sekundy wysyła na hub pełną diagnostykę: listę ekranów, urządzenia USB,
wersję SDK, stan pulpitu, trackingu i helpera ADB, plus własny log. Hub pokazuje to jako
checklistę z podpowiedzią przy każdym ✕ — również zdalnie, bez kabla i `adb logcat`.

To samo widać na telefonie w nagłówku (Okulary / Komputer / Pulpit / Głowa), a surowe dane
są pod `GET /api/diag`.

## Pierwsze uruchomienie

1. Włącz **Opcje programisty → Wireless Debugging**.
2. Sparuj GlassOS 6-cyfrowym kodem (powiadomienie, nie zamykaj okna parowania).
3. Podłącz Luma USB-C i zaakceptuj USB.
4. Opcjonalnie: Ustawienia → Ułatwienia dostępu → GlassOS (klawiatura w polach tekstowych na okularach).

## Sterowanie

**Telefon**

- 1 palec — kursor
- tap — klik
- przytrzymaj — przeciąganie okna / prawy klik
- 2 palce — scroll
- pinch — zoom workspace
- Recenter / Head cursor / 2D·3D — dolny pasek

**Klawiatura Bluetooth** (jak w UxSpace)

| Skrót | Akcja |
|---|---|
| Ctrl+Alt+X | PINNED / FREE |
| Ctrl+Alt+R | Recenter SDK |
| Ctrl+Alt+C | Kotwica pionowa |
| Ctrl+Alt+A | Kolejny układ |
| Ctrl+Alt+Z | Pas ekranu |
| Ctrl+Alt + scroll | Zoom |

## Architektura repozytorium

```
app/        GlassOS — Compose na telefonie, launcher, helper ADB
glasses/    VITURE USB + JNI (prawdziwy SDK albo stub)
spatial/    Presentation, OpenGL workspace, układy, kursor
docs/       architektura UxSpace (warstwa silnika)
```

Pakiet wewnętrzny zostaje `com.uxspace` (JNI, AIDL, helper). Nazwa aplikacji: **GlassOS**.

SceneView / Filament, OCR z kamery Luma Pro i pełny spatial UI to etap 2.

## Licencja

Kod aplikacji: Apache-2.0 (fork UxSpace).  
SDK VITURE: własność VITURE, nie jest częścią tego repozytorium.
