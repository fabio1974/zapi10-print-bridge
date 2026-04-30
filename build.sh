#!/bin/bash
# Build dos zips de distribuição (Windows + Mac) — roda no macOS.
# Pré-requisitos:
#   - JDK 21 instalado (recomendo: brew install openjdk@21)
#   - Maven (brew install maven OU usar tarball portátil)
#   - curl, unzip, zip
#
# Uso: ./build.sh
# Saída: dist/zapi10-print-bridge-windows.zip
#        dist/zapi10-print-bridge-mac.zip

set -e

cd "$(dirname "$0")"

# ===== Detectar JDK 21 =====
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME/jmods" ]; then
    echo "ERRO: JDK 21 não encontrado. Instale com: brew install openjdk@21"
    exit 1
fi
echo "JDK: $JAVA_HOME"

# ===== Detectar Maven =====
MVN=$(command -v mvn || true)
if [ -z "$MVN" ] && [ -x /tmp/apache-maven-3.9.9/bin/mvn ]; then
    MVN=/tmp/apache-maven-3.9.9/bin/mvn
fi
if [ -z "$MVN" ]; then
    echo "ERRO: Maven não encontrado. Instale com: brew install maven"
    exit 1
fi
echo "Maven: $MVN"

# ===== 1. Compilar jar =====
echo ""
echo "=== [1/5] Compilando jar (Maven) ==="
JAVA_HOME="$JAVA_HOME" "$MVN" -B -q clean package
echo "OK: target/print-bridge.jar"

# ===== 2. JRE Mac =====
echo ""
echo "=== [2/5] Gerando JRE mínima para Mac ==="
rm -rf installer/mac/jre
"$JAVA_HOME/bin/jlink" \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.logging,jdk.httpserver \
  --strip-debug --no-man-pages --no-header-files --compress=zip-9 \
  --output installer/mac/jre
echo "OK: installer/mac/jre/ ($(du -sh installer/mac/jre | cut -f1))"

# ===== 3. JRE Windows (cross-jlink) =====
echo ""
echo "=== [3/5] Gerando JRE mínima para Windows (cross-jlink) ==="
WIN_JDK_VER="21.0.5_11"
WIN_JDK_DIR=/tmp/jdk-21.0.5+11
WIN_JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.zip"

if [ ! -d "$WIN_JDK_DIR/jmods" ]; then
    echo "Baixando Temurin 21 Windows JDK (~190MB, uma vez só)..."
    curl -fsSL -o /tmp/temurin21-win.zip "$WIN_JDK_URL"
    cd /tmp && unzip -q -o temurin21-win.zip && cd - > /dev/null
fi

rm -rf installer/windows/jre
"$JAVA_HOME/bin/jlink" \
  --module-path "$WIN_JDK_DIR/jmods" \
  --add-modules java.base,java.desktop,java.logging,jdk.httpserver \
  --strip-debug --no-man-pages --no-header-files --compress=zip-9 \
  --output installer/windows/jre
echo "OK: installer/windows/jre/ ($(du -sh installer/windows/jre | cut -f1))"

# ===== 4. Stage e zipar Mac =====
echo ""
echo "=== [4/5] Empacotando zip Mac ==="
rm -rf installer/mac/logs installer/windows/logs
cp target/print-bridge.jar installer/mac/
chmod +x installer/mac/*.command
mkdir -p dist
rm -f dist/zapi10-print-bridge-mac.zip
( cd installer/mac && zip -qr ../../dist/zapi10-print-bridge-mac.zip \
  print-bridge.jar com.zapi10.printbridge.plist \
  install.command uninstall.command view-logs.command \
  LEIA-ME.txt jre/ )
echo "OK: dist/zapi10-print-bridge-mac.zip ($(du -sh dist/zapi10-print-bridge-mac.zip | cut -f1))"

# ===== 5. Stage e zipar Windows =====
echo ""
echo "=== [5/5] Empacotando zip Windows ==="
cp target/print-bridge.jar installer/windows/

# Garante que o WinSW está presente
if [ ! -f installer/windows/print-bridge.exe ]; then
    echo "Baixando WinSW..."
    curl -fsSL -o installer/windows/print-bridge.exe \
      https://github.com/winsw/winsw/releases/download/v3.0.0-alpha.11/WinSW-net461.exe
fi

rm -f dist/zapi10-print-bridge-windows.zip
( cd installer/windows && zip -qr ../../dist/zapi10-print-bridge-windows.zip \
  print-bridge.jar print-bridge.exe print-bridge.xml \
  install.bat uninstall.bat view-logs.bat \
  LEIA-ME.txt jre/ )
echo "OK: dist/zapi10-print-bridge-windows.zip ($(du -sh dist/zapi10-print-bridge-windows.zip | cut -f1))"

echo ""
echo "============================================================"
echo "  BUILD CONCLUÍDO"
echo "============================================================"
ls -lh dist/
echo ""
echo "Próximos passos:"
echo "  - Testar Mac: extrair zip, duplo-clique em install.command"
echo "  - Enviar Windows zip pro dono do Tex Burger por email/WhatsApp"
echo "  - Pra .exe instalador único: tag git v1.0.0 e push (CI builda)"
