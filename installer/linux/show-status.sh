#!/bin/bash
# Mostra status atual do Zapi10 Print Bridge: porta + IPs locais

SERVICE_FILE="/etc/systemd/system/zapi10-print-bridge.service"

# Le a porta do systemd unit
if [ -f "$SERVICE_FILE" ]; then
    PORT=$(grep PRINT_BRIDGE_PORT "$SERVICE_FILE" | grep -oE '=[0-9]+' | tr -d '=')
fi
[ -z "$PORT" ] && PORT=9100

# Verifica se esta rodando
if systemctl is-active --quiet zapi10-print-bridge 2>/dev/null; then
    STATUS="RODANDO"
else
    STATUS="PARADO"
fi

echo ""
echo "============================================================"
echo "  Zapi10 Print Bridge - Status"
echo "============================================================"
echo ""
echo "Servico:    $STATUS"
echo "Porta:      $PORT"
echo ""
echo "IPs deste PC (configure um deles no app Zapi10):"
echo "----------------------------------------"
ip -4 addr show 2>/dev/null | awk -v port="$PORT" '/inet / && $2 !~ /^127\./ {split($2,a,"/"); print "  " a[1] ":" port}'
echo ""
echo "Para ver os logs detalhados: ./view-logs.sh"
echo "Status detalhado: systemctl status zapi10-print-bridge"
echo ""
