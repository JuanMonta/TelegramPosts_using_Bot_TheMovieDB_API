<#
.SYNOPSIS
  Build-And-Launch.ps1 -- compila (menú Producción/Desarrollo) y abre la app.
  La consola de compilación se cierra sola al terminar; la app queda en segundo plano.
.EXAMPLE
  .\Build-And-Launch.ps1
  .\Build-And-Launch.ps1 -Modo dev -SinMenu
#>
param(
  [ValidateSet('prod', 'dev')][string]$Modo,
  [int]$Port,
  [switch]$SinMenu
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'CanalVideos.Tools.ps1')

$build = Join-Path $root 'Build-CanalVideos.ps1'
$launch = Join-Path $root 'Launch-CanalVideos.ps1'
if (-not (Test-Path -LiteralPath $build)) { throw "No se encontró $build" }
if (-not (Test-Path -LiteralPath $launch)) { throw "No se encontró $launch" }

if (-not $Port -or $Port -le 0) { $Port = 9000 }
$psExe = Join-Path $PSHOME 'powershell.exe'
if (-not (Test-Path -LiteralPath $psExe)) { $psExe = 'powershell.exe' }

# El build se ejecuta en un proceso hijo: un fallo no cierra esta ventana de forma abrupta
# y el código de salida se puede interpretar para mostrar una ayuda clara.
$argsBuild = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $build)
if ($Modo) { $argsBuild += @('-Modo', $Modo) }
if ($SinMenu) { $argsBuild += '-SinMenu' }
try {
  & $psExe @argsBuild
  if ($LASTEXITCODE -ne 0) { throw "El build terminó con código $LASTEXITCODE." }
} catch {
  Write-Host ''
  Write-Host ("[ERROR] {0}" -f $_.Exception.Message) -ForegroundColor Red
  Write-Host 'No se abrirá la aplicación porque la compilación no terminó correctamente.' -ForegroundColor Yellow
  exit 1
}

$jar = Get-JarPath
if (-not $jar -or -not (Test-JarValido $jar)) {
  Write-Host '[ERROR] El build no generó un jar válido. Vuelve a intentarlo.' -ForegroundColor Red
  exit 1
}

if (-not (Test-PuertoLibre $Port)) {
  $ocupado = Get-ProcesoPuerto $Port
  $libre = Find-PuertoLibre 9000 9010
  if ($libre -le 0) {
    Write-Host ("[ERROR] El puerto {0} está ocupado y no hay otro libre cerca." -f $Port) -ForegroundColor Red
    Write-Host 'Cierra la aplicación que lo usa y vuelve a intentarlo.' -ForegroundColor Yellow
    exit 1
  }
  $nombre = if ($ocupado) { "{0} (PID {1})" -f $ocupado.Nombre, $ocupado.Pid } else { 'otro proceso' }
  Write-Aviso (" El puerto {0} está ocupado por {1}. Se usará el {2}." -f $Port, $nombre, $libre)
  $Port = $libre
}

Write-Paso ("Abriendo CanalStudio en http://localhost:{0} ..." -f $Port)
$launcherProc = $null
try {
  $launcherProc = Start-AppDetached -Puerto $Port -JarPath $jar
} catch {
  Write-Host ("[ERROR] No se pudo iniciar la aplicación: {0}" -f $_.Exception.Message) -ForegroundColor Red
  exit 1
}

if (-not (Wait-AppReady -Puerto $Port -Segundos 90 -Launcher $launcherProc)) {
  $logDir = Join-Path $root 'logs'
  Show-BackendFailure (Join-Path $logDir ("api-{0}-error.log" -f $Port)) (Join-Path $logDir ("api-{0}.log" -f $Port))
  exit 1
}

Write-Host ''
Write-Ok (" Listo: CanalStudio está abierta en http://localhost:{0}" -f $Port)
Write-Host ' Esta ventana se cerrará sola.' -ForegroundColor DarkGray
Start-Sleep -Milliseconds 900
exit 0
