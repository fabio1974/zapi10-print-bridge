@echo off
setlocal

cd /d "%~dp0"

REM Le a porta do print-bridge.xml
for /f "delims=" %%P in ('powershell -NoProfile -Command "([xml](Get-Content \"%~dp0\print-bridge.xml\")).service.env ^| Where-Object { $_.name -eq 'PRINT_BRIDGE_PORT' } ^| Select-Object -ExpandProperty value"') do (
    set "PORT=%%P"
)
if "%PORT%"=="" set "PORT=9100"

REM Verifica se o servico esta rodando
sc query zapi10-print-bridge | findstr "RUNNING" >nul
if %errorlevel% == 0 (
    set "STATUS=RODANDO"
) else (
    set "STATUS=PARADO"
)

echo.
echo ============================================================
echo   Zapi10 Print Bridge - Status
echo ============================================================
echo.
echo Servico:    %STATUS%
echo Porta:      %PORT%
echo.
echo IPs deste PC (configure um deles no app Zapi10):
echo ----------------------------------------
for /f "tokens=2 delims=:" %%A in ('ipconfig ^| findstr /R /C:"IPv4"') do (
    echo   %%A:%PORT%
)
echo.
echo Para ver os logs detalhados: clique duplo em "view-logs.bat"
echo.
pause
