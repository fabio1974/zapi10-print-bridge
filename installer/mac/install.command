#!/bin/bash
# Zapi10 Print Bridge - Instalador para macOS
# Duplo-clique neste arquivo. Vai pedir senha de administrador.

set -e

cd "$(dirname "$0")"

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Instalando no macOS"
echo "============================================================"
echo ""

if [ ! -f "print-bridge.jar" ]; then
    echo "ERRO: print-bridge.jar nao encontrado nesta pasta."
    echo "Verifique se voce extraiu o ZIP completamente."
    exit 1
fi

if [ ! -d "jre" ]; then
    echo "ERRO: pasta 'jre' nao encontrada."
    echo "Verifique se voce extraiu o ZIP completamente."
    exit 1
fi

INSTALL_DIR="/usr/local/zapi10-print-bridge"
PLIST_USER="$HOME/Library/LaunchAgents/com.zapi10.printbridge.plist"

# ===== Escolhe automaticamente uma porta livre =====
PORT=""
for try in 9100 9110 9111 9112 9113 9114 9115 9116 9117 9118 9119 9120; do
    if ! lsof -nP -iTCP:$try -sTCP:LISTEN >/dev/null 2>&1; then
        PORT=$try
        break
    fi
done

if [ -z "$PORT" ]; then
    echo "ERRO: nenhuma porta livre encontrada entre 9100 e 9120."
    echo "Verifique com 'lsof -nP -iTCP -sTCP:LISTEN' quem esta ocupando."
    exit 1
fi

if [ "$PORT" != "9100" ]; then
    echo "Porta 9100 ocupada — usando porta $PORT como alternativa."
    sed -i.bak "/<key>PRINT_BRIDGE_PORT<\/key>/{n;s|<string>9100</string>|<string>$PORT</string>|;}" com.zapi10.printbridge.plist
    rm -f com.zapi10.printbridge.plist.bak
    echo ""
fi

echo "[1/5] Vai pedir sua senha de administrador (sudo)..."
sudo -v

echo "[2/5] Parando instalacao anterior se existir..."
launchctl unload "$PLIST_USER" 2>/dev/null || true

echo "[3/5] Copiando arquivos para $INSTALL_DIR..."
sudo rm -rf "$INSTALL_DIR"
sudo mkdir -p "$INSTALL_DIR"
sudo cp print-bridge.jar "$INSTALL_DIR/"
sudo cp -R jre "$INSTALL_DIR/"
sudo mkdir -p "$INSTALL_DIR/logs"
sudo chmod -R 755 "$INSTALL_DIR"
sudo chmod -R 777 "$INSTALL_DIR/logs"

echo "[4/5] Configurando autostart (launchd)..."
mkdir -p "$HOME/Library/LaunchAgents"
cp com.zapi10.printbridge.plist "$PLIST_USER"
chmod 644 "$PLIST_USER"

echo "[5/5] Iniciando o servico..."
launchctl load "$PLIST_USER"
sleep 2

if pgrep -f "print-bridge.jar" > /dev/null; then
    echo ""
    echo "============================================================"
    echo "  INSTALACAO CONCLUIDA COM SUCESSO"
    echo "============================================================"
    echo ""
    echo "O servico 'Zapi10 Print Bridge' esta rodando em background."
    echo "Inicia automaticamente quando voce loga no Mac."
    echo ""
    echo "PORTA EM USO: $PORT"
    echo "Configure no app Zapi10 mobile: <IP-deste-Mac>:$PORT"
    echo ""
    echo "Logs em: $INSTALL_DIR/logs"
    echo "Para ver porta + IPs: duplo-clique em 'show-status.command'"
    echo "Para ver logs: duplo-clique em 'view-logs.command'"
    echo "Para desinstalar: duplo-clique em 'uninstall.command'"
    echo ""
else
    echo ""
    echo "AVISO: O servico parece nao ter iniciado. Verifique os logs:"
    echo "  $INSTALL_DIR/logs/"
    echo ""
fi

echo ""
echo "Pressione Enter para fechar..."
read -r
