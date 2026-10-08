# Zdalne komputery w okularach — hub na serwerze, monitory przez Tailscale

Cel: podłączasz okulary do telefonu i masz gotowe monitory z Twoimi komputerami
(RDP, Moonlight, VNC, SSH), z klawiaturą i myszą Bluetooth jak przy biurku. Hub stoi
na serwerze w tailnecie i jest tylko pilotem. Obraz idzie **bezpośrednio** z komputera
do telefonu.

```
          Tailscale (tailnet)
┌─────────────┐   HTTPS    ┌──────────────┐
│  Serwer     │◄──────────►│  Telefon     │──USB-C──► Luma Pro
│  GlassOS Hub│  komendy   │  GlassOS     │           ekran 1  ekran 2  ekran 3
└─────────────┘            └──────┬───────┘
      ▲ przeglądarka              │ RDP / Moonlight / VNC — wprost
      │ (układasz pulpity)        ▼
┌─────┴───────┐            ┌──────────────┐  ┌──────────────┐
│ laptop / tel│            │ PC praca     │  │ PC dom (Home)│
└─────────────┘            │ Windows Pro  │  │ Sunshine     │
                           └──────────────┘  └──────────────┘
```

## Co dostajesz

| Rzecz | Jak działa |
|---|---|
| **Komputer na ekranie** | Kafel „+ Komputer” w hubie. Telefon otwiera klienta na danym ekranie okularów od razu na pełnym monitorze 1920×1080, bez ramki okna. |
| **Profil pulpitu** | Np. „Praca” = ekran 1 serwer firmowy (RDP), ekran 2 Twój PC (Moonlight), ekran 3 przeglądarka. Jeden klik **Włącz na okularach**. |
| **Autostart** | Zaznacz „Włączaj ten pulpit sam, gdy podłączę okulary”. Telefon trzyma kopię profilu, więc działa też, gdy hub akurat nie odpowiada. |
| **Zielona kropka** | Hub co 8 s sprawdza port każdego komputera i pokazuje, kto żyje. |
| **Obudź** | Wake-on-LAN z huba (tylko gdy serwer stoi w tej samej sieci LAN co komputer). |
| **→ 1 / → 2 / → 3** | Otwórz komputer na wybranym ekranie od ręki, bez zmiany zapisanego profilu. |
| **Mysz jak w Windows** | Mysz Bluetooth nad zdalnym pulpitem to prawdziwy wskaźnik: kursor Windows jedzie za Tobą, prawy klik, przeciąganie, kółko. Przełącznik w Ustawieniach okularów: *Mouse as real pointer in apps*. |
| **Klawiatura** | Klawiatura Bluetooth sparowana z telefonem pisze bezpośrednio do klienta. |

## Który klient do którego komputera

| Komputer | Rodzaj w hubie | Klient na telefonie | Uwagi |
|---|---|---|---|
| Windows 10/11 **Pro**, Server, maszyny firmowe | `RDP` | **Windows App** (dawniej Microsoft Remote Desktop) | Najlepszy tekst, wiele sesji. Home nie ma serwera RDP. |
| Windows **Home** (np. Twój PC), gry, wideo | `Moonlight` | **Moonlight** + na PC **Sunshine** | Najniższe opóźnienie, 1080p60, pełna klawiatura i mysz. |
| Linux / Mac z pulpitem | `VNC` lub `Moonlight` | **AVNC** / **bVNC**, albo Moonlight | Sunshine działa też na Linuksie i Macu. |
| Cokolwiek z shellem | `SSH` | przeglądarka (strona huba) | Polecenia wykonuje hub, wynik widzisz w oknie. |

---

## 1. Serwer z hubem

Dowolna maszyna, która jest w Twoim tailnecie i działa 24/7: mini-PC, Raspberry Pi,
NAS z Pythonem, VPS. Jeśli chcesz **budzić komputery (WOL)**, serwer musi stać w tej
samej sieci domowej co one.

Wymagania: Python 3.11+, git, Tailscale. Hub nie ma zewnętrznych zależności (stdlib).

### Linux (Debian/Ubuntu/Raspberry Pi OS)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --hostname glassos-hub
git clone https://github.com/xcequx/GlassOS.git ~/glassos
cd ~/glassos/tools/hub/deploy
sudo bash install-linux.sh
```

Skrypt instaluje usługę systemd `glassos-hub` (autostart po restarcie), kopiuje
`.env.example`, i włącza `tailscale serve`, który wystawia hub pod
`https://glassos-hub.<twoj-tailnet>.ts.net` — z certyfikatem, tylko w tailnecie.

Aktualizacja: `cd ~/glassos && git pull && sudo systemctl restart glassos-hub`.

### Windows (jeśli serwerem jest PC z Windows)

```powershell
# Tailscale: https://tailscale.com/download/windows, zaloguj się
git clone https://github.com/xcequx/GlassOS.git C:\glassos
powershell -ExecutionPolicy Bypass -File C:\glassos\tools\hub\deploy\register-task.ps1
```

Rejestruje zadanie harmonogramu, które startuje hub przy uruchomieniu systemu, oraz
`tailscale serve --bg 30100`.

### Klucze AI (opcjonalnie)

`tools/hub/.env`:

```
DEEPSEEK_API_KEY=sk-…
```

### Tailscale — ACL (zalecane)

Przykład w `tools/hub/deploy/tailscale-acl.example.json`: telefon może do komputerów
tylko na porty RDP / Moonlight / VNC / SSH, każdy może do huba, nic więcej. Wklejasz w
panelu Tailscale → Access Controls.

---

## 2. Komputery docelowe

### Windows Pro / Server — RDP

1. Ustawienia → System → Pulpit zdalny → **Włącz**.
2. Tailscale na tym komputerze, zapamiętaj jego nazwę (np. `pc-praca`).
3. W hubie: rodzaj **RDP**, host `pc-praca`, użytkownik Windows. Hasło wpiszesz raz w
   Windows App na telefonie, zapamięta.

### Windows Home (i każdy PC do niskiego opóźnienia) — Sunshine + Moonlight

1. Na PC zainstaluj **Sunshine** (https://app.lizardbyte.dev), otwórz
   `https://localhost:47990`, ustaw login.
2. W Sunshine → Applications zostaw **Desktop** (jest domyślnie).
3. Na telefonie zainstaluj **Moonlight**, dodaj PC po nazwie Tailscale, sparuj PIN-em
   w panelu Sunshine. Zrób to **raz, ręcznie** — potem GlassOS wchodzi sam.
4. W hubie: rodzaj **Moonlight**, host `pc-dom`.
   Opcjonalnie **UUID**: w pliku `C:\Program Files\Sunshine\config\sunshine_state.json`
   pole `uniqueid`. Z UUID GlassOS wchodzi prosto w stream pulpitu; bez niego otworzy
   Moonlight z listą komputerów i tapniesz PC jeden raz.
5. Rozdzielczość w Moonlight: 1920×1080, 60 fps, bitrate 20–30 Mb/s w Wi‑Fi.

Windows Home **nie ma** serwera RDP — Sunshine to właściwa droga.

### Linux / Mac — VNC

Serwer VNC (np. TigerVNC, macOS „Udostępnianie ekranu”), na telefonie AVNC albo bVNC.
W hubie: rodzaj **VNC**, host, port (domyślnie 5900).

### SSH

Serwer potrzebuje klienta `ssh` i klucza bez hasła do komputera. W hubie: rodzaj
**SSH**, host, użytkownik. Kafel „+ SSH” otwiera konsolę w oknie na ekranie.

---

## 3. Telefon

1. GlassOS ≥ 1.2.0. Hub podaje APK w nagłówku strony i telefon sam proponuje
   aktualizację — pod warunkiem, że plik jest na serwerze: APK nie jest w repo
   (za duży), więc po buildzie wrzuć `app/build/outputs/apk/debug/app-debug.apk` jako
   `tools/hub/static/GlassOS.apk` na serwer (`tools/hub/publish-apk.ps1` robi to na PC
   z buildem; na serwer skopiuj `GlassOS.apk` i `version.json` przez scp).
2. Tailscale na telefonie, zalogowany do tego samego tailnetu, **włączony VPN**.
3. Zakładka AI → Gateway = adres huba (`https://glassos-hub.<tailnet>.ts.net`).
4. **Helper ADB** (Debugowanie bezprzewodowe, parowanie kodem) — potrzebny, żeby
   otwierać aplikacje na ekranach okularów. Robisz raz; po restarcie telefonu trzeba
   włączyć Debugowanie bezprzewodowe ponownie (hub pokaże ✕ przy „ADB”).
5. Zainstaluj klientów: **Windows App** i/lub **Moonlight** (i AVNC, jeśli VNC).
6. Sparuj klawiaturę i mysz Bluetooth z telefonem.

---

## 4. Jak to działa na co dzień

1. Na komputerze (albo z telefonu w przeglądarce) otwórz hub, ułóż profil, zaznacz
   autostart, kliknij **Włącz na okularach**.
2. Podłączasz okulary do telefonu kablem USB-C. GlassOS stawia pulpit, po ~4 s sam
   wdraża profil autostartu: na każdym ekranie wstaje klient i łączy się z komputerem.
3. Patrzysz na ekran, na którym chcesz pracować, i po prostu piszesz. Mysz przeskakuje
   między ekranami jak między monitorami.
4. Zmiana komputera na ekranie: hub → **→ 1/2/3** przy komputerze. Wejdzie w ≤ 2 s.
5. Odłączasz okulary — sesje zdalne się kończą (klienci dostają rozłączenie), nic nie
   zostaje na ekranie telefonu.

### Czy potrzebny jest internet?

- **Tailscale potrzebuje internetu**, żeby urządzenia się odnalazły i wymieniły klucze
  (serwer koordynacyjny). Po nawiązaniu połączenia ruch w LAN idzie bezpośrednio,
  a w drodze — przez najlepszą trasę, czasem przez relay DERP.
- **Bez huba** (serwer leży, ale tailnet działa): autostart i tak zadziała, bo telefon
  ma kopię profilu i listy komputerów. Nie zadziała tylko sterowanie z przeglądarki.
- **Bez internetu w ogóle** (tylko domowe Wi‑Fi): wpisz w hubie **adresy LAN**
  (np. `192.168.1.20`) zamiast nazw Tailscale, a telefon i serwer trzymaj w tej samej
  sieci. Wtedy nic nie wychodzi na zewnątrz.
- **Komórkowe 5G/LTE**: działa, opóźnienie 40–100 ms. RDP i SSH są na to odporne,
  Moonlight obniż do 10–15 Mb/s.

### Opóźnienie

| Trasa | Typowo |
|---|---|
| LAN, Moonlight | 10–25 ms |
| LAN, RDP | 30–60 ms |
| Tailscale bezpośrednio (różne sieci) | 40–90 ms |
| przez relay DERP | 80–150 ms |

Do biura, kodu i terminala — bez różnicy. Do wideo i grafiki — Moonlight.

---

## 5. Klawiatura i mysz — szczegóły

- **Mysz**: nad zdalnym pulpitem GlassOS wstrzykuje prawdziwe zdarzenia wskaźnika
  (hover, lewy/prawy/środkowy, przeciąganie, kółko). Nad pulpitem okularów (tapeta,
  pasek) mysz działa jak dotąd: klik = klik, prawy = menu. Wyłączenie: Ustawienia
  okularów → *Interaction* → *Mouse as real pointer in apps*.
- **Klawiatura**: wszystko idzie do klienta oprócz skrótów GlassOS `Ctrl+Alt+X/R/C/A/Z/+/-`,
  które łapie helper (klient je też dostaje — w Windows rzadko coś znaczą).
- **Win / Meta**: na Samsungu klawisz Win jest przypięty do launchera telefonu. W Windows
  App użyj paska skrótów klienta albo `Ctrl+Esc`.
- **Ctrl+Alt+Del**: przez menu klienta (Windows App ma je w pasku).
- **Trackpad w telefonie** nadal działa: tap = klik, przytrzymanie = prawy klik.

---

## 6. Gdy coś nie działa

| Objaw | Co sprawdzić |
|---|---|
| Kafel komputera nic nie otwiera | Hub → Stan połączenia → **ADB** musi być ✓. Log telefonu pokaże „potrzebny helper ADB” albo „Zainstaluj Windows App/Moonlight”. |
| Windows App otwiera się, ale nie łączy | Czerwona kropka przy komputerze = port nie odpowiada. Tailscale na obu końcach? RDP włączony? Zapora Windows (port 3389) z Tailscale dozwolona? |
| Moonlight pokazuje listę zamiast pulpitu | Brak UUID albo nie ten; PC musi być **sparowany** w Moonlight wcześniej. |
| Obraz jest, mysz nie trafia / nie ma prawego klawisza | Ustawienia → *Mouse as real pointer in apps* włączone; Windows App w trybie **Mouse/Pointer** (ikonka w pasku klienta). |
| Klawiatura pisze w telefonie, nie w okularach | Zamknij pole tekstowe GlassOS na telefonie (fokus); klient na ekranie musi być na wierzchu — kliknij w niego myszą. |
| Autostart nie wszedł | Profil zaznaczony w hubie? Telefon miał kontakt z hubem choć raz po zaznaczeniu? Helper ADB gotowy (autostart próbuje 4 razy co 5 s)? |
| WOL nie budzi | Hub musi być w tym samym LAN; w BIOS/sterowniku karty włączone Wake on Magic Packet. |

Surowe dane: `GET /api/computers` (status portów), `GET /api/logs` (log telefonu).

## API dla skryptów

| Metoda | Ścieżka | Do czego |
|---|---|---|
| GET | `/api/computers` | lista z `online` / `ms` |
| POST | `/api/computers` | `{name, host, user, port, kind, mac, uuid, app}` |
| PUT | `/api/computers/<id>` | edycja |
| POST | `/api/computers/<id>/delete` | usunięcie (czyści kafle) |
| POST | `/api/computers/<id>/wake` | Wake-on-LAN |
| POST | `/api/computers/<id>/open` | `{"screenIdx":0}` — komputer na ekran teraz |
| POST | `/api/settings` | `{"autostart_desktop":"<id pulpitu>"}` |
| POST | `/api/command` | `{"type":"open_url","url":"https://…","screenIdx":1}` |
