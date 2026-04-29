#!/bin/bash
# Abre o log mais recente do Zapi10 Print Bridge

LOG_DIR="/usr/local/zapi10-print-bridge/logs"

if [ ! -d "$LOG_DIR" ]; then
    echo "Pasta de logs nao encontrada: $LOG_DIR"
    echo "Voce ja instalou o Zapi10 Print Bridge?"
    read -r
    exit 1
fi

LATEST=$(ls -t "$LOG_DIR"/print-bridge-*.log 2>/dev/null | head -1)

if [ -z "$LATEST" ]; then
    echo "Nenhum log encontrado em $LOG_DIR"
    echo "Se acabou de instalar, aguarde alguns segundos."
    read -r
    exit 1
fi

echo "Abrindo: $LATEST"
echo "(Ctrl+C pra fechar o tail. Pressione Enter pra ver o log inteiro depois)"
echo ""
tail -f "$LATEST"
