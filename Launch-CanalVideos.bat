@echo off
chcp 65001 >nul
REM ======================================================================
REM  Launch-CanalVideos.bat -- modo app (--app) desde RAIZ
REM  Lanza el PS1 completamente oculto (sin ventana negra) via wscript.
REM  Solo quedan splash + tray + ventana app (Chrome/Edge --app).
REM  Uso: Launch-CanalVideos.bat [-Multi] [-Port 9000]
REM  Sin flags: instancia única (si ya está abierta, la trae al frente y sale).
REM  -Multi: modo pruebas, permite varias instancias (puertos auto-incrementales).
REM ======================================================================
set "PS1=%~dp0Launch-CanalVideos.ps1"
if not exist "%PS1%" (
  echo [ERROR] No se encontro Launch-CanalVideos.ps1 al lado de este .bat
  pause
  exit /b 1
)
set "ARGS=%*"
REM Usa wscript para lanzar PowerShell sin ventana (evita Windows Terminal)
set "VBS=%TEMP%\CanalVideos_launch.vbs"
> "%VBS%" echo Set WshShell = CreateObject("WScript.Shell")
>> "%VBS%" echo WshShell.Run "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""%PS1%"" %ARGS%", 0, False
cscript //nologo "%VBS%" >nul 2>&1
REM Fallback si wscript falla (ejecuta visible pero con splash)
if errorlevel 1 powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%PS1%" %*
exit /b 0
