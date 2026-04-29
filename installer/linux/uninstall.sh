#!/bin/bash
# Zapi10 Print Bridge - Desinstalador Linux

set -e

if [ "$EUID" -ne 0 ]; then
    echo "ERRO: rode como root. Ex:  sudo ./uninstall.sh"
    exit 1
fi

INSTALL_DIR="/usr/local/zapi10-print-bridge"
SERVICE_FILE="/etc/systemd/system/zapi10-print-bridge.service"

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Desinstalando"
echo "============================================================"
echo ""

echo "[1/3] Parando e desabilitando servico..."
systemctl stop zapi10-print-bridge 2>/dev/null || true
systemctl disable zapi10-print-bridge 2>/dev/null || true
rm -f "$SERVICE_FILE"
systemctl daemon-reload

echo "[2/3] Removendo arquivos..."
rm -rf "$INSTALL_DIR"

echo "[3/3] Removendo regra de firewall (se aplicavel)..."
if command -v ufw >/dev/null 2>&1; then
    ufw delete allow 9100/tcp >/dev/null 2>&1 || true
    ufw delete allow 9110/tcp >/dev/null 2>&1 || true
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --remove-port=9100/tcp >/dev/null 2>&1 || true
    firewall-cmd --permanent --remove-port=9110/tcp >/dev/null 2>&1 || true
    firewall-cmd --reload >/dev/null 2>&1 || true
fi

echo ""
echo "============================================================"
echo "  DESINSTALACAO CONCLUIDA"
echo "============================================================"
echo ""
