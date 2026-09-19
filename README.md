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

## Uruchomienie i test (telefon + komputer)

Pełna instrukcja kamery/AI: **[docs/START.md](docs/START.md)**.

Skrót:

1. Na PC: `python tools/ai-gateway/server.py` (klucz `XAI_API_KEY` w `tools/ai-gateway/.env`).
2. W Android Studio wgraj GlassOS na telefon.
3. Sparuj Wireless Debugging, podłącz Luma Pro USB-C.
4. Zakładka **AI** → źródło **Luma Pro** → Gateway `http://IP_KOMPUTERA:30100` → **Test PC** → **Co widzę**.

Kamera Luma Pro to UVC (`0x0C45:0x636B`). Jeśli telefon nie wystawi jej jako Camera2, pipeline AI działa też z kamerą telefonu — ten sam gateway.

## Budowa

Wymagania: JDK 17+, Android Studio (API 36), NDK `30.0.14904198`, CMake `4.1.2`.

```powershell
# local.properties — ścieżka do Android SDK
sdk.dir=C:\\Users\\USER\\AppData\\Local\\Android\\Sdk

.\gradlew.bat :app:installDebug
```

Bez vendored SDK projekt i tak się buduje. Tracking i pokrętła sprzętowe są wtedy nieaktywne, a sam pulpit + touchpad na zewnętrznym ekranie działają.

SDK: patrz [`glasses/VENDOR_SDK.md`](glasses/VENDOR_SDK.md).

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
