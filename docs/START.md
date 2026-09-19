# Jak uruchomić i przetestować GlassOS + kamerę Luma Pro

Masz **Luma Pro** — kamera pass-through jest osobnym urządzeniem USB (UVC 1920×1080 @ 30 fps, VID `0x0C45` / PID `0x636B`). GlassOS pokazuje podgląd na telefonie, wysyła klatki na komputer, a odpowiedź AI wraca jako overlay w okularach.

```
Luma Pro (USB-C)
   ├── DisplayPort  → pulpit GlassOS w okularach
   ├── IMU 0x35CA   → 3DoF / head cursor
   └── kamera UVC   → zakładka AI na telefonie
                          │  Wi-Fi
                          ▼
                   PC :30100  (gateway)
                          │
                          ▼
                   SpaceXAI (xAI)  — opcjonalnie
```

Na tym PC nie ma Android SDK, więc APK budujesz u siebie w Android Studio.

---

## 0. Co musisz mieć

| Gdzie | Co |
|---|---|
| Telefon | Android 11+, USB-C DisplayPort, to samo Wi-Fi co komputer |
| Okulary | VITURE Luma Pro + kabel USB-C |
| Komputer | Python 3.10+, Windows / macOS / Linux |
| Android Studio | JDK 17, SDK API 36, NDK `30.0.14904198`, CMake `4.1.2` |
| AI | klucz z https://console.x.ai (opcjonalnie na pierwszy test kamery) |

---

## 1. Komputer — gateway AI

W PowerShell, w folderze projektu:

```powershell
cd C:\Users\User\Desktop\Glasses\tools\ai-gateway
copy .env.example .env
notepad .env
```

W `.env` wstaw:

```
XAI_API_KEY=xai-twoj-klucz
GLASSOS_AI_MODEL=grok-4.5
PORT=30100
```

Klucz: https://accounts.x.ai → https://console.x.ai

Uruchom:

```powershell
python server.py
```

Powinno być:

```
GlassOS AI gateway  http://0.0.0.0:30100
  XAI_API_KEY OK
```

Bez klucza gateway też wstaje — wtedy testujesz tylko, czy klatki dochodzą.

### IP komputera (to wpiszesz w telefonie)

```powershell
ipconfig
```

Szukaj `IPv4` karty Wi-Fi, np. `192.168.1.24`.

### Zapora Windows (inaczej telefon nie wejdzie)

PowerShell **jako administrator**:

```powershell
netsh advfirewall firewall add rule name="GlassOS AI" dir=in action=allow protocol=tcp localport=30100
```

### Test z komputera

W drugiej konsoli:

```powershell
curl http://127.0.0.1:30100/health
```

Czekasz na `"ok": true`.

---

## 2. Telefon — zbuduj i wgraj GlassOS

1. Otwórz `C:\Users\User\Desktop\Glasses` w Android Studio.
2. Skopiuj `local.properties.example` → `local.properties` i ustaw `sdk.dir`.
3. Podłącz telefon kablem (na chwilę, bez okularów) albo Wireless Debugging.
4. Run / `.\gradlew.bat :app:installDebug`.

Pierwsze uruchomienie aplikacji:

1. Odblokuj **Opcje programisty**.
2. Włącz **Wireless Debugging**.
3. Sparuj 6-cyfrowym kodem w powiadomieniu GlassOS.
4. Zgoda na **kamerę**.

Potem odłącz kabel od komputera i włącz Luma Pro do USB-C telefonu.

---

## 3. Test 1 — pulpit w okularach (bez AI)

1. Załóż Luma Pro, USB-C w telefon.
2. Dashboard: model **Luma Pro**, zielona kropka, rozdzielczość.
3. Touchpad: kursor w okularach.
4. Recenter, 2/3 ekrany w zakładce Ekrany.

Jeśli nie ma obrazu: inny kabel USB-C (musi mieć DisplayPort), na Samsungu czasem „DeX / HDMI” w powiadomieniu USB.

---

## 4. Test 2 — kamera Luma Pro

W GlassOS otwórz zakładkę **AI**.

Dashboard / etykieta kamery powinna pokazać coś w stylu:

`Luma Pro UVC  pid=0x636b`

1. Zezwól na USB kamery, jeśli system pyta.
2. Na liście źródeł wybierz **Luma Pro** (albo „Kamera USB”).
3. W podglądzie masz widzieć to, na co patrzysz okularami — nie ekran telefonu.

Jeśli nie ma „Luma Pro”, a jest tylko „Telefon tył”:

- telefon nie wystawił UVC jako Camera2 (częste na niektórych OEM),
- na razie wybierz **Telefon tył** i testuj cały łańcuch AI,
- potem spróbuj innego telefonu (Pixel zwykle widzi UVC) albo vendored VITURE SDK (`glasses/VENDOR_SDK.md`).

---

## 5. Test 3 — AI „co widzę”

1. Na komputerze `python server.py` nadal działa.
2. W zakładce AI w polu **Gateway na PC** wpisz `http://IP_KOMPUTERA:30100`  
   przykład: `http://192.168.1.24:30100`
3. **Test PC** → status `OK`.
4. (Opcjonalnie) **Live** — klatki lecą same, gdy obraz się zmienia.
5. Spójrz okularami na klawiaturę / monitor / kartkę.
6. **Co widzę**.

Odpowiedź:

- na telefonie pod przyciskiem,
- przez ~10 s jako napis w okularach.

**Odczytaj** — to samo, ale z prośbą o tekst z tabliczki / UI / urządzenia.

Na komputerze w konsoli gatewaya zobaczysz:

```
[gateway] frame session=glassos-… bytes=…
[gateway] chat session=… prompt='Co widzę?' -> …
```

---

## 6. Co znaczy który wynik

| Objaw | Co jest nie tak |
|---|---|
| `/health` nie działa na PC | Python / port |
| Test PC na telefonie = błąd | zły IP, inna sieć Wi-Fi, zapora |
| Brak UVC, jest tylko kamera telefonu | OEM nie dał Camera2 EXTERNAL — pipeline AI i tak działa |
| Podgląd czarny | brak zgody CAMERA / USB kamery, albo źródło nie to |
| Chat: „brak XAI_API_KEY” | klatki dochodzą, brakuje klucza w `.env` |
| Chat 502 | klucz, model albo sieć do api.x.ai |
| Overlay nie widać w okularach | najpierw Test 1 (pulpit musi działać) |

---

## 7. VisualClaw (opcjonalnie, zamiast naszego gatewaya)

Nasz `tools/ai-gateway` mówi tym samym protokołem co VisualClaw (`/v1/video/frame`, `/v1/glasses/chat`). Żeby użyć oryginału:

```powershell
git clone https://github.com/UCSC-VLAA/VisualClaw.git
cd VisualClaw
pip install -e ".[live,video]"
# config.yaml: live.enabled + glasses_http_enabled
visualclaw start
```

W telefonie ten sam URL `:30100`. Do testów produktywności zostaw `tools/ai-gateway` — jest lżejszy i od razu woła Grok.

---

## 8. SDK VITURE (head tracking, jasność)

Kamera w v1 idzie przez Camera2/UVC i **nie wymaga** `libglasses.so`.  
Tracking 3DoF i suwaki jasności — tak, patrz `glasses/VENDOR_SDK.md`.
