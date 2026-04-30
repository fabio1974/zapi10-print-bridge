#!/bin/bash
# Mostra status atual do Zapi10 Print Bridge: porta + IPs locais

PLIST_USER="$HOME/Library/LaunchAgents/com.zapi10.printbridge.plist"

if [ ! -f "$PLIST_USER" ]; then
    echo "Bridge nao instalado. Rode install.command primeiro."
    read -r
    exit 1
fi

# Le a porta do plist
PORT=$(/usr/libexec/PlistBuddy -c "Print :EnvironmentVariables:PRINT_BRIDGE_PORT" "$PLIST_USER" 2>/dev/null)
[ -z "$PORT" ] && PORT=9100

# Verifica se esta rodando
if pgrep -f "print-bridge.jar" >/dev/null 2>&1; then
    STATUS="RODANDO"
else
    STATUS="PARADO"
fi

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Status"
echo "============================================================"
echo ""
echo "Servico:        $STATUS"
echo "Porta TCP:      $PORT   (mobile)"
echo "Porta HTTP:     9101    (browser)"
echo ""
echo "IPs deste Mac (configure um deles no app Zapi10):"
echo "----------------------------------------"
ifconfig | awk -v port="$PORT" '/inet / && $2 != "127.0.0.1" {print "  " $2 ":" port}'
echo ""
echo "Para ver os logs detalhados: duplo-clique em 'view-logs.command'"
echo ""
echo "Pressione Enter pra fechar..."
read -r
