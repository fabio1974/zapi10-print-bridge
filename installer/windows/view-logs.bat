@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0logs"

if not exist "*.log" (
    echo Nenhum log encontrado em %~dp0logs
    echo.
    echo Se acabou de instalar, aguarde alguns segundos e tente de novo.
    pause
    exit /b 1
)

REM Pega o log mais recente (ordem alfabetica = ordem cronologica com YYYY-MM-DD)
set "LATEST="
for /f "delims=" %%F in ('dir /b /o-n print-bridge-*.log 2^>nul') do (
    set "LATEST=%%F"
    goto :found
)

:found
if "%LATEST%"=="" (
    echo Nenhum arquivo print-bridge-*.log encontrado.
    pause
    exit /b 1
)

echo Abrindo: %LATEST%
notepad "%LATEST%"
