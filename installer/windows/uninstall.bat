@echo off
setlocal

net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo ERRO: Este script precisa ser executado como Administrador.
    echo.
    echo Clique direito em uninstall.bat ^-^> "Executar como administrador"
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"

echo.
echo ============================================================
echo   Zapi10 Print Bridge - Desinstalando
echo ============================================================
echo.

echo [1/3] Parando servico...
print-bridge.exe stop >nul 2>&1

echo [2/3] Removendo servico...
print-bridge.exe uninstall

echo [3/3] Removendo regra do firewall...
netsh advfirewall firewall delete rule name="Zapi10 Print Bridge" >nul 2>&1

echo.
echo ============================================================
echo   DESINSTALACAO CONCLUIDA
echo ============================================================
echo.
echo Os arquivos da pasta atual NAO foram removidos.
echo Voce pode apagar a pasta manualmente se desejar.
echo Logs ficaram em: %~dp0logs
echo.
pause
