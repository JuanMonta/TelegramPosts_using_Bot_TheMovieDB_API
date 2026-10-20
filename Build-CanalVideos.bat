@echo off
chcp 65001 >nul
REM ======================================================================
REM  Build-CanalVideos.bat -- compila el frontend Angular + empaqueta el jar
REM  Ubicado en la RAIZ del proyecto (CanalVideos\)
REM  No hace falta instalar Node, Java ni Maven: el script descarga
REM  versiones portátiles dentro de tools\ y runtime\ si faltan.
REM  Uso: Build-CanalVideos.bat [-Modo prod^|dev] [-SkipFrontend] [-SinMenu]
REM ======================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build-CanalVideos.ps1" %*
set "RC=%errorlevel%"
if not "%RC%"=="0" (
  echo.
  echo [ERROR] El build no termino correctamente. Revisa los mensajes de arriba.
)
pause
exit /b %RC%
