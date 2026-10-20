@echo off
chcp 65001 >nul
REM ======================================================================
REM  Build-And-Launch.bat -- compila y abre CanalVideos en un solo paso.
REM  Al terminar, esta ventana se cierra automaticamente.
REM  Uso: Build-And-Launch.bat [-Modo prod^|dev] [-Port 9000] [-SinMenu]
REM ======================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build-And-Launch.ps1" %*
set "RC=%errorlevel%"
if not "%RC%"=="0" (
  echo.
  echo [ERROR] No se pudo compilar o iniciar la aplicacion.
  pause
)
exit /b %RC%
