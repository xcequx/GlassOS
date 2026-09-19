# Pulpit z komputera — GlassOS Hub

To jest główny sposób pracy: **układasz ekrany i aplikacje w przeglądarce**, telefon wdraża to w okularach.

```
Przeglądarka na PC / serwerze
        │  http://IP:30100
        ▼
   GlassOS Hub
        │  Wi-Fi
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

IP:

```powershell
ipconfig
```

Zapora (admin):

```powershell
netsh advfirewall firewall add rule name="GlassOS Hub" dir=in action=allow protocol=tcp localport=30100
```

Opcjonalnie AI (ta sama instancja): skopiuj `tools/ai-gateway/.env.example` do `tools/hub/.env` i wstaw `XAI_API_KEY`.

## Telefon

1. Wgraj GlassOS, sparuj Wireless Debugging, podłącz Luma Pro.
2. Zakładka **AI** albo status huba: Gateway `http://IP_KOMPUTERA:30100`
3. **Test PC** — ma wrócić OK.
4. Zakładka **Ekrany** — pojawią się pulpity z komputera (`Praca`, `Focus`, `Kino`).

## Jak układać

1. Na PC: z lewej wybierz pulpit albo **Nowy pulpit**.
2. Układ: 1 / 2 / 3 ekrany, Focus, Kino.
3. Z prawej przeciągnij aplikacje z telefonu na **Ekran 1 / 2 / 3**.
4. **Zapisz**, potem **Włącz na okularach**.
5. GlassOS ustawia layout i odpala apki na właściwych ekranach.

To samo **Włącz** jest na telefonie w zakładce Ekrany.

Recenter z przeglądarki = przycisk Recenter (albo na telefonie).

## Serwer 24/7

Na Linuxie / NAS:

```bash
cd tools/hub
python3 server.py --host 0.0.0.0 --port 30100
```

Albo systemd / docker — to zwykły HTTP na 30100, bez bazy. Stan jest w `tools/hub/data/state.json`.
