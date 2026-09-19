# GlassOS AI gateway

Lokalny serwer na PC. Telefon streamuje klatki z Luma Pro, tutaj wołany jest Grok.

```powershell
copy .env.example .env
# wstaw XAI_API_KEY
python server.py
curl http://127.0.0.1:30100/health
```

Szczegóły: [docs/START.md](../../docs/START.md)
