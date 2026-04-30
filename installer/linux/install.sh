#!/bin/bash
# Zapi10 Print Bridge - Instalador para Linux (systemd)
# Uso: sudo ./install.sh

set -e

cd "$(dirname "$0")"

if [ "$EUID" -ne 0 ]; then
    echo "ERRO: rode como root. Ex:  sudo ./install.sh"
    exit 1
fi

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Instalando no Linux (systemd)"
echo "============================================================"
echo ""

if [ ! -f "print-bridge.jar" ] || [ ! -d "jre" ]; then
    echo "ERRO: print-bridge.jar ou pasta jre nao encontrados."
    echo "Verifique se voce extraiu o tar.gz completamente."
    exit 1
fi

INSTALL_DIR="/usr/local/zapi10-print-bridge"
SERVICE_FILE="/etc/systemd/system/zapi10-print-bridge.service"

# Escolhe automaticamente uma porta livre
PORT=""
for try in 9100 9110 9111 9112 9113 9114 9115 9116 9117 9118 9119 9120; do
    if ! ss -ltn "( sport = :$try )" 2>/dev/null | grep -q LISTEN; then
        PORT=$try
        break
    fi
done

if [ -z "$PORT" ]; then
    echo "ERRO: nenhuma porta livre entre 9100 e 9120."
    exit 1
fi

if [ "$PORT" != "9100" ]; then
    echo "Porta 9100 ocupada — usando porta $PORT como alternativa."
    sed -i "s|PRINT_BRIDGE_PORT=9100|PRINT_BRIDGE_PORT=$PORT|" zapi10-print-bridge.service
fi

echo "[1/5] Parando servico anterior se existir..."
systemctl stop zapi10-print-bridge 2>/dev/null || true
systemctl disable zapi10-print-bridge 2>/dev/null || true

echo "[2/5] Copiando arquivos para $INSTALL_DIR..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp print-bridge.jar "$INSTALL_DIR/"
cp -R jre "$INSTALL_DIR/"
mkdir -p "$INSTALL_DIR/logs"
chmod -R 755 "$INSTALL_DIR"

echo "[3/5] Instalando systemd unit..."
cp zapi10-print-bridge.service "$SERVICE_FILE"
systemctl daemon-reload

echo "[4/5] Abrindo portas $PORT e 9101 no firewall (se aplicavel)..."
if command -v ufw >/dev/null 2>&1; then
    ufw allow "$PORT"/tcp >/dev/null 2>&1 || true
    ufw allow 9101/tcp >/dev/null 2>&1 || true
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null 2>&1 || true
    firewall-cmd --permanent --add-port=9101/tcp >/dev/null 2>&1 || true
    firewall-cmd --reload >/dev/null 2>&1 || true
fi

echo "[5/5] Habilitando autostart e iniciando..."
systemctl enable zapi10-print-bridge
systemctl start zapi10-print-bridge

sleep 2
if systemctl is-active --quiet zapi10-print-bridge; then
    echo ""
    echo "============================================================"
    echo "  INSTALACAO CONCLUIDA COM SUCESSO"
    echo "============================================================"
    echo ""
    echo "Servico ativo. Inicia automaticamente no boot."
    echo ""
    echo "PORTA EM USO: $PORT"
    echo "Configure no app Zapi10 mobile: <IP-deste-PC>:$PORT"
    echo ""
    echo "Ver porta + IPs: ./show-status.sh"
    echo "Logs:            $INSTALL_DIR/logs/"
    echo "Status systemd:  systemctl status zapi10-print-bridge"
    echo "Ver logs:        ./view-logs.sh"
    echo "Desinstalar:     sudo ./uninstall.sh"
    echo ""
else
    echo ""
    echo "AVISO: Servico nao iniciou. Verifique:"
    echo "  systemctl status zapi10-print-bridge"
    echo "  journalctl -u zapi10-print-bridge"
    echo ""
fi
