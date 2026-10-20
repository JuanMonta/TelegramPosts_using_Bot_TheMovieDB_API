@echo off
chcp 65001 >nul
REM ======================================================================
REM  AsistenteDeCompilacion-CanalVideos.bat -- asistente de compilación y arranque.
REM  Revisa requisitos, compila si hace falta y abre CanalVideos.
REM  Uso: AsistenteDeCompilacion-CanalVideos.bat [-Auto] [-Diagnostico] [-Modo prod^|dev]
REM ======================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0AsistenteDeCompilacion-CanalVideos.ps1" %*
set "RC=%errorlevel%"
if not "%RC%"=="0" (
  echo.
  echo [ERROR] El asistente no pudo completar la operacion.
  pause
)
exit /b %RC%
