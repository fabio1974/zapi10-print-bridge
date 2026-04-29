#!/bin/bash
# Zapi10 Print Bridge - Desinstalador macOS

set -e

INSTALL_DIR="/usr/local/zapi10-print-bridge"
PLIST_USER="$HOME/Library/LaunchAgents/com.zapi10.printbridge.plist"

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Desinstalando"
echo "============================================================"
echo ""

echo "[1/3] Vai pedir sua senha de administrador (sudo)..."
sudo -v

echo "[2/3] Parando servico..."
launchctl unload "$PLIST_USER" 2>/dev/null || true
rm -f "$PLIST_USER"

echo "[3/3] Removendo arquivos..."
sudo rm -rf "$INSTALL_DIR"

echo ""
echo "============================================================"
echo "  DESINSTALACAO CONCLUIDA"
echo "============================================================"
echo ""
echo "Pressione Enter para fechar..."
read -r
