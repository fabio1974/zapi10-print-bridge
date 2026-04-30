@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM Zapi10 Print Bridge - Instalador para Windows
REM Clique direito neste arquivo -> "Executar como administrador"
REM ============================================================

net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo ERRO: Este script precisa ser executado como Administrador.
    echo.
    echo Clique direito em install.bat ^-^> "Executar como administrador"
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"

echo.
echo ============================================================
echo   Zapi10 Print Bridge - Instalando como servico Windows
echo ============================================================
echo.

if not exist "jre\bin\javaw.exe" (
    echo ERRO: JRE nao encontrada em "jre\bin\javaw.exe"
    echo Verifique que o ZIP foi extraido completamente.
    echo.
    pause
    exit /b 1
)

if not exist "print-bridge.jar" (
    echo ERRO: print-bridge.jar nao encontrado.
    echo Verifique que o ZIP foi extraido completamente.
    echo.
    pause
    exit /b 1
)

REM ===== Escolhe automaticamente uma porta livre =====
set "PORT="
for %%P in (9100 9110 9111 9112 9113 9114 9115 9116 9117 9118 9119 9120) do (
    if not defined PORT (
        netstat -ano | findstr "LISTENING" | findstr ":%%P " >nul 2>&1
        if errorlevel 1 set "PORT=%%P"
    )
)

if not defined PORT (
    echo ERRO: nenhuma porta livre encontrada entre 9100 e 9120.
    echo Verifique quais programas estao usando essas portas e tente novamente.
    pause
    exit /b 1
)

if not "!PORT!"=="9100" (
    echo Porta 9100 ocupada — usando porta !PORT! como alternativa.
    powershell -NoProfile -Command "(Get-Content print-bridge.xml -Raw) -replace 'PRINT_BRIDGE_PORT\" value=\"9100\"', 'PRINT_BRIDGE_PORT\" value=\"!PORT!\"' | Set-Content print-bridge.xml -NoNewline"
    echo.
)

echo [1/4] Removendo instalacao anterior se existir...
print-bridge.exe stop  >nul 2>&1
print-bridge.exe uninstall >nul 2>&1

echo [2/4] Instalando servico Windows...
print-bridge.exe install
if errorlevel 1 (
    echo ERRO: Falha ao instalar o servico.
    pause
    exit /b 1
)

echo [3/4] Abrindo portas !PORT! e 9101 no firewall do Windows...
netsh advfirewall firewall delete rule name="Zapi10 Print Bridge" >nul 2>&1
netsh advfirewall firewall delete rule name="Zapi10 Print Bridge HTTP" >nul 2>&1
netsh advfirewall firewall add rule name="Zapi10 Print Bridge" dir=in action=allow protocol=TCP localport=!PORT! >nul
netsh advfirewall firewall add rule name="Zapi10 Print Bridge HTTP" dir=in action=allow protocol=TCP localport=9101 >nul

echo [4/4] Iniciando servico...
print-bridge.exe start
if errorlevel 1 (
    echo AVISO: Falha ao iniciar o servico. Tente reiniciar o Windows.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   INSTALACAO CONCLUIDA COM SUCESSO
echo ============================================================
echo.
echo O servico "Zapi10 Print Bridge" agora roda em background.
echo Ele inicia automaticamente quando o Windows liga.
echo.
echo PORTA TCP (mobile):  !PORT!
echo PORTA HTTP (browser): 9101
echo Configure no app Zapi10 mobile: ^<IP-deste-PC^>:!PORT!
echo.
echo Logs em: %~dp0logs
echo Para ver porta + IPs: clique duplo em "show-status.bat"
echo Para ver logs: clique duplo em "view-logs.bat"
echo Para desinstalar: clique direito em "uninstall.bat" - Executar como admin
echo.
pause
