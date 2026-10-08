# Agent roboczy

Agent, który naprawdę robi rzeczy na komputerze, a postęp widzisz na ekranie w okularach.

Pomysł z [sam-siavoshian/claude-code-g2](https://github.com/sam-siavoshian/claude-code-g2)
(Claude Code sterowany głosem z okularów Even Realities G2). Tam silnikiem było lokalne CLI
`claude`, transkrypcją Whisper, a wyjściem mały HUD 576×288. Tutaj:

| | claude-code-g2 | GlassOS |
|---|---|---|
| Model | Claude CLI (subskrypcja) | **DeepSeek** (`DEEPSEEK_API_KEY`) |
| Backend | Bun + Express | hub, który już masz (`tools/hub`) |
| Ekran | HUD 576×288 | pełny 1080p pulpit w Luma Pro |
| Głos | Whisper (płatny) | Web Speech API w przeglądarce (za darmo) |
| Transport | SSE | SSE |

## Uruchomienie

Agent startuje razem z hubem — nic dodatkowego nie trzeba włączać.

```powershell
python C:\Users\User\Desktop\Glasses\tools\hub\server.py
```

Strona: **`/agent.html`** (przycisk **AGENT** w nagłówku huba).

Na okularach: w hubie jest gotowy pulpit **Agent** — ekran 1 to agent, ekran 2 to hub.
Kliknij **Włącz na okularach**.

## Jak pracować

1. Napisz zadanie albo kliknij **🎤 Mów** i powiedz je po polsku.
2. Agent pokazuje każdy krok: jakie polecenie wykonuje i co z niego wyszło.
3. Gdy chce coś **zmienić** — uruchomić polecenie inne niż odczyt albo zapisać plik —
   zatrzymuje się i czeka na Twoje **Wykonaj / Odrzuć**.
4. Na koniec dostajesz jedno zdanie podsumowania.

## Co agent potrafi

| Narzędzie | Do czego |
|---|---|
| `run` | polecenie powłoki (cmd.exe) w katalogu z białej listy |
| `read_file` | odczyt pliku albo jego fragmentu |
| `write_file` | zapis pliku — **zawsze za zgodą** |
| `list_dir` | zawartość katalogu |
| `search` | szukanie tekstu w plikach |
| `glasses` | przełączanie pulpitów, układu, recenter, pasek zadań |

## Bezpieczeństwo

To jest agent z dostępem do powłoki — zasady są celowo ciasne:

- **Biała lista katalogów.** Domyślnie tylko `C:\Users\User\Desktop\Glasses`. Zmieniasz przez
  `AGENT_ROOTS` w `tools/hub/.env` (kilka ścieżek rozdziel średnikiem). Ścieżka poza listą =
  odmowa, niezależnie od tego, o co poprosisz.
- **Odczyt leci od razu, reszta za zgodą.** `git status`, `dir`, `type`, `findstr` i podobne
  wykonują się bez pytania. Wszystko inne — instalacje, build, git commit, kasowanie, zapis
  pliku — czeka na kliknięcie.
- **Twarde blokady.** `rm -rf /`, `format`, `diskpart`, `shutdown`, `reg delete` i kilka innych
  są odrzucane nawet po akceptacji.
- **Limit kroków i czasu.** 12 kroków na zadanie, 120 s na polecenie.
- **Hub jest tylko w tailnecie.** `tailscale serve` nie wystawia go do internetu — agent jest
  osiągalny wyłącznie z Twoich urządzeń.

Brak decyzji przez 5 minut = agent traktuje to jak odmowę i idzie dalej.

## Konfiguracja

`tools/hub/.env`:

```
DEEPSEEK_API_KEY=sk-…
DEEPSEEK_MODEL=deepseek-flash
AGENT_MODEL=deepseek-flash        # opcjonalnie: inny model dla agenta niż dla czatu
AGENT_ROOTS=C:\Users\User\Desktop\Glasses;C:\Users\User\Desktop\Praca
```

## API

| Metoda | Ścieżka | Do czego |
|---|---|---|
| POST | `/api/agent/ask` | `{"text":"…"}` → `{"id":"…"}` — start zadania |
| GET | `/api/agent/stream?id=…` | strumień SSE zdarzeń |
| POST | `/api/agent/approve` | `{"id":"…","ok":true}` — zgoda albo odmowa |
| GET | `/api/agent/runs` | ostatnie przebiegi + katalogi z białej listy |

Rodzaje zdarzeń: `start`, `text`, `tool`, `result`, `approval`, `approval_done`,
`blocked`, `error`, `done`, `ping`.
