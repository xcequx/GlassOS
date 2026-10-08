#!/usr/bin/env bash
# Install GlassOS Hub as a systemd service and expose it inside the tailnet.
# Run from the repo:  sudo bash tools/hub/deploy/install-linux.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HUB_DIR="$(cd "$HERE/.." && pwd)"
RUN_USER="${SUDO_USER:-$(id -un)}"
PORT="${PORT:-30100}"

if ! command -v python3 >/dev/null; then
  echo "Brak python3 — zainstaluj (apt install python3) i uruchom ponownie." >&2
  exit 1
fi
PY="$(command -v python3)"
VER="$($PY -c 'import sys;print("%d.%d"%sys.version_info[:2])')"
case "$VER" in
  3.1[1-9]|3.[2-9]*) ;;
  *) echo "Potrzebny Python >= 3.11, jest $VER." >&2; exit 1 ;;
esac

mkdir -p "$HUB_DIR/data"
chown -R "$RUN_USER" "$HUB_DIR/data"
if [ ! -f "$HUB_DIR/.env" ] && [ -f "$HUB_DIR/../ai-gateway/.env.example" ]; then
  cp "$HUB_DIR/../ai-gateway/.env.example" "$HUB_DIR/.env"
  chown "$RUN_USER" "$HUB_DIR/.env"
  echo "Utworzono $HUB_DIR/.env — wstaw tam DEEPSEEK_API_KEY, jeśli chcesz asystenta."
fi

UNIT=/etc/systemd/system/glassos-hub.service
sed -e "s|__USER__|$RUN_USER|g" \
    -e "s|__HUB_DIR__|$HUB_DIR|g" \
    -e "s|__PYTHON__|$PY|g" \
    -e "s|__PORT__|$PORT|g" \
    "$HERE/glassos-hub.service" > "$UNIT"
systemctl daemon-reload
systemctl enable --now glassos-hub
sleep 2
systemctl --no-pager --lines=5 status glassos-hub || true

if command -v tailscale >/dev/null; then
  if tailscale serve --bg "$PORT" >/dev/null 2>&1; then
    echo
    echo "Hub w tailnecie: $(tailscale serve status 2>/dev/null | head -1)"
  else
    echo "tailscale serve nie zadziałał — włącz HTTPS w panelu Tailscale (DNS → HTTPS Certificates) i powtórz: tailscale serve --bg $PORT"
  fi
else
  echo "Tailscale nie jest zainstalowany: curl -fsSL https://tailscale.com/install.sh | sh ; tailscale up"
fi

echo
echo "Lokalnie: http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT"
echo "Log:      journalctl -u glassos-hub -f"
