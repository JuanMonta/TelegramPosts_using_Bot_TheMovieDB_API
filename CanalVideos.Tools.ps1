# ============================================================
#  CanalVideos.Tools.ps1 -- utilidades compartidas por los scripts
#  de compilación y arranque (herramientas portátiles, descargas,
#  clave maestra y parada de la app).
#
#  Se usa con dot-sourcing:  . (Join-Path $PSScriptRoot 'CanalVideos.Tools.ps1')
# ============================================================

$script:CanalVideosRoot = $PSScriptRoot
$script:CanalVideosTools = Join-Path $PSScriptRoot 'tools'
$script:CanalVideosRuntime = Join-Path $PSScriptRoot 'runtime'
$script:CanalVideosJarName = 'crow-0.0.1-SNAPSHOT.jar'
$script:JavaMajorCache = @{}
$script:JavacMajorCache = @{}
$script:NodeMajorCache = @{}
$script:VCRedistNombre = 'Microsoft Visual C++ 2015-2022 Redistributable (x64)'
$script:VCRedistVersion = '14.x'
$script:VCRedistUrl = 'https://aka.ms/vs/17/release/vc_redist.x64.exe'
$script:VCRedistDir = Join-Path $PSScriptRoot 'prerequisites\vcredist'
$script:VCRedistExe = Join-Path $script:VCRedistDir 'VC_redist.x64.exe'
$script:IconosFuente = @(
  'angular_frontend\src\favicon.ico',
  'angular_frontend\src\assets\icon.png',
  'angular_frontend\src\assets\icons\canalvideos-16.png',
  'angular_frontend\src\assets\icons\canalvideos-24.png',
  'angular_frontend\src\assets\icons\canalvideos-32.png',
  'angular_frontend\src\assets\icons\canalvideos-48.png',
  'angular_frontend\src\assets\icons\canalvideos-64.png',
  'angular_frontend\src\assets\icons\canalvideos-128.png',
  'angular_frontend\src\assets\icons\canalvideos-256.png',
  'angular_frontend\src\assets\icons\canalvideos-512.png',
  'angular_frontend\src\assets\icons\canalvideos.svg'
)
$script:IconosDerivados = [ordered]@{
  'CanalVideos.ico' = 'angular_frontend\src\favicon.ico'
  'CanalVideos.png' = 'angular_frontend\src\assets\icons\canalvideos-512.png'
  'angular_frontend\public\favicon.ico' = 'angular_frontend\src\favicon.ico'
}

try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
try { $global:OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
if (-not [Console]::IsInputRedirected) {
  try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}
}

function Invoke-ExeTexto([string]$Exe, [string]$Argumentos) {
  try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = $Argumentos
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $err = $p.StandardError.ReadToEnd()
    $out = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    return ($err + "`n" + $out)
  } catch { return '' }
}

function Get-JavaMajor([string]$Exe) {
  if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) { return 0 }
  if ($script:JavaMajorCache.ContainsKey($Exe)) { return $script:JavaMajorCache[$Exe] }
  $texto = Invoke-ExeTexto $Exe '-version'
  $major = 0
  if ($texto -match 'version "(\d+)(?:\.(\d+))?') {
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
  }
  $script:JavaMajorCache[$Exe] = $major
  return $major
}

function Get-JavacMajor([string]$Exe) {
  if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) { return 0 }
  if ($script:JavacMajorCache.ContainsKey($Exe)) { return $script:JavacMajorCache[$Exe] }
  $texto = Invoke-ExeTexto $Exe '-version'
  $major = 0
  if ($texto -match 'javac\s+(\d+)') { $major = [int]$Matches[1] }
  $script:JavacMajorCache[$Exe] = $major
  return $major
}

function Get-NodeMajor([string]$Exe) {
  if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) { return 0 }
  if ($script:NodeMajorCache.ContainsKey($Exe)) { return $script:NodeMajorCache[$Exe] }
  $texto = Invoke-ExeTexto $Exe '-v'
  $major = 0
  if ($texto -match 'v(\d+)\.') { $major = [int]$Matches[1] }
  $script:NodeMajorCache[$Exe] = $major
  return $major
}

function Write-Paso([string]$Texto) { Write-Host $Texto -ForegroundColor Cyan }
function Write-Ok([string]$Texto) { Write-Host $Texto -ForegroundColor Green }
function Write-Aviso([string]$Texto) { Write-Host $Texto -ForegroundColor Yellow }

function Show-Mensaje([string]$Texto, [string]$Titulo = 'CanalVideos') {
  $redirigido = $false
  try { $redirigido = [Console]::IsInputRedirected } catch {}
  if ($redirigido -or $env:CV_SIN_UI -eq '1') { return $false }
  try {
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
    [void][System.Windows.Forms.MessageBox]::Show($Texto, $Titulo,
      [System.Windows.Forms.MessageBoxButtons]::OK,
      [System.Windows.Forms.MessageBoxIcon]::Information)
    return $true
  } catch { return $false }
}

function Invoke-Descarga([string]$Url, [string]$Destino, [string]$Etiqueta) {
  $dir = Split-Path -Parent $Destino
  if ($dir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  Write-Paso ("  Descargando {0}..." -f $Etiqueta)
  $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
  if ($curl) {
    & $curl.Source '-L' '--fail' '--retry' '3' '--connect-timeout' '20' '-#' '-o' $Destino $Url
    if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar $Etiqueta (curl $LASTEXITCODE)." }
  } else {
    $viejo = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try { Invoke-WebRequest -Uri $Url -OutFile $Destino -UseBasicParsing } finally { $ProgressPreference = $viejo }
  }
  if (-not (Test-Path -LiteralPath $Destino)) { throw "La descarga de $Etiqueta no generó el archivo esperado." }
  if ((Get-Item -LiteralPath $Destino).Length -lt 1MB) { throw "La descarga de $Etiqueta parece incompleta." }
}

function Expandir-Paquete([string]$Zip, [string]$Destino, [string]$ExeRelativo) {
  $tmp = Join-Path $env:TEMP ('cv_pkg_' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmp -Force | Out-Null
  try {
    $tar = Get-Command 'tar.exe' -ErrorAction SilentlyContinue
    if ($tar) {
      & $tar.Source '-xf' $Zip '-C' $tmp
      if ($LASTEXITCODE -ne 0) { throw "No se pudo extraer $Zip" }
    } else {
      Expand-Archive -LiteralPath $Zip -DestinationPath $tmp -Force
    }
    $nombreExe = Split-Path $ExeRelativo -Leaf
    if ($ExeRelativo -like '*\*') {
      $exe = Get-ChildItem -LiteralPath $tmp -Recurse -File -Filter $nombreExe |
        Where-Object { $_.FullName -like "*\$(Split-Path $ExeRelativo -Parent)\$nombreExe" } |
        Select-Object -First 1
    } else {
      $exe = Get-ChildItem -LiteralPath $tmp -Recurse -File -Filter $nombreExe | Select-Object -First 1
    }
    if (-not $exe) { throw "El paquete descargado no contiene $ExeRelativo." }
    $raiz = if ($ExeRelativo -like '*\*') { $exe.Directory.Parent.FullName } else { $exe.Directory.FullName }
    if (Test-Path -LiteralPath $Destino) { Remove-Item -LiteralPath $Destino -Recurse -Force }
    New-Item -ItemType Directory -Path $Destino -Force | Out-Null
    Get-ChildItem -LiteralPath $raiz -Force | Move-Item -Destination $Destino -Force
    if (-not (Test-Path -LiteralPath (Join-Path $Destino $ExeRelativo))) {
      throw "El paquete descargado no quedó bien instalado en $Destino."
    }
  } finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
  }
}

function Get-JavaLocal([switch]$Silencioso) {
  $candidatos = @(
    (Join-Path $script:CanalVideosRuntime 'bin\java.exe'),
    (Join-Path $script:CanalVideosTools 'jdk17\bin\java.exe'),
    (Join-Path $script:CanalVideosTools 'jre\bin\java.exe')
  )
  if ($env:JAVA_HOME) { $candidatos += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
  $cmd = Get-Command 'java.exe' -ErrorAction SilentlyContinue
  if ($cmd -and $cmd.Source -and $cmd.Source -notlike '*\WindowsApps\*') { $candidatos += $cmd.Source }
  foreach ($c in $candidatos) {
    if (-not $c -or -not (Test-Path -LiteralPath $c)) { continue }
    $major = Get-JavaMajor $c
    if ($major -ge 17) { return $c }
    if (-not $Silencioso -and $major -gt 0) {
      Write-Aviso (" Java {0} en {1} es antiguo; se necesita Java 17 o superior." -f $major, $c)
    }
  }
  return $null
}

function Install-JavaRuntime {
  $zip = Join-Path $env:TEMP ('cv_jre_' + [guid]::NewGuid().ToString('N') + '.zip')
  try {
    $url = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse'
    Invoke-Descarga -Url $url -Destino $zip -Etiqueta 'Java portátil (JRE 17)'
    Expandir-Paquete -Zip $zip -Destino $script:CanalVideosRuntime -ExeRelativo 'bin\java.exe'
  } finally {
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
  }
  return (Join-Path $script:CanalVideosRuntime 'bin\java.exe')
}

function Resolve-JavaRuntime {
  $java = Get-JavaLocal
  if ($java) { return $java }
  Write-Aviso ' Java no encontrado en el equipo.'
  [void](Show-Mensaje ("Primera ejecución: se descargará un Java portátil (JRE 17, unos 50 MB).`n`n" +
    "Solo ocurre una vez y necesita conexión a internet."))
  Install-JavaRuntime | Out-Null
  $java = Join-Path $script:CanalVideosRuntime 'bin\java.exe'
  if (-not (Test-Path -LiteralPath $java)) { throw 'No se pudo preparar Java portátil.' }
  Write-Ok (" Java portátil listo: {0}" -f $java)
  return $java
}

function Test-JdkHome([string]$Ruta) {
  if (-not $Ruta) { return $false }
  return (Test-Path -LiteralPath (Join-Path $Ruta 'bin\javac.exe'))
}

function Get-JdkLocal([switch]$Silencioso) {
  $candidatos = @(
    (Join-Path $script:CanalVideosTools 'jdk17'),
    $env:JAVA_HOME
  )
  $javac = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
  if ($javac -and $javac.Source -and $javac.Source -notlike '*\WindowsApps\*') {
    $candidatos += (Split-Path (Split-Path $javac.Source -Parent) -Parent)
  }
  foreach ($c in $candidatos) {
    if (-not (Test-JdkHome $c)) { continue }
    $major = Get-JavacMajor (Join-Path $c 'bin\javac.exe')
    if ($major -ge 17) { return (Resolve-Path -LiteralPath $c).Path }
    if (-not $Silencioso -and $major -gt 0) {
      Write-Aviso (" JDK {0} en {1} es antiguo; se necesita JDK 17 o superior." -f $major, $c)
    }
  }
  return $null
}

function Install-Jdk {
  $zip = Join-Path $env:TEMP ('cv_jdk_' + [guid]::NewGuid().ToString('N') + '.zip')
  $destino = Join-Path $script:CanalVideosTools 'jdk17'
  try {
    $url = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
    Invoke-Descarga -Url $url -Destino $zip -Etiqueta 'JDK 17 portátil'
    Expandir-Paquete -Zip $zip -Destino $destino -ExeRelativo 'bin\javac.exe'
  } finally {
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
  }
  return $destino
}

function Resolve-Jdk {
  $jdk = Get-JdkLocal
  if ($jdk) { return $jdk }
  Write-Aviso ' JDK 17 no encontrado o es demasiado antiguo.'
  Write-Paso ' Se descargará un JDK portátil solo para compilar (unos 190 MB, primera vez).'
  return (Install-Jdk)
}

function Get-NodeInfoLocal([switch]$Silencioso) {
  $dirNode = Join-Path $script:CanalVideosTools 'node'
  $candidatos = @(
    @{ Node = (Join-Path $dirNode 'node.exe'); Npm = (Join-Path $dirNode 'npm.cmd') }
  )
  $node = Get-Command 'node.exe' -ErrorAction SilentlyContinue
  $npm = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
  if ($node -and $npm) { $candidatos += @{ Node = $node.Source; Npm = $npm.Source } }
  foreach ($c in $candidatos) {
    if (-not (Test-Path -LiteralPath $c.Node) -or -not (Test-Path -LiteralPath $c.Npm)) { continue }
    $major = Get-NodeMajor $c.Node
    if ($major -ge 14) { return $c }
    if (-not $Silencioso -and $major -gt 0) {
      Write-Aviso (" Node.js {0} en {1} es antiguo; se necesita Node 14 o superior." -f $major, $c.Node)
    }
  }
  return $null
}

function Resolve-Node {
  $local = Get-NodeInfoLocal
  if ($local) { return $local }

  $dirNode = Join-Path $script:CanalVideosTools 'node'
  $nodePortatil = Join-Path $dirNode 'node.exe'
  $nodePortatilOk = (Test-Path -LiteralPath $nodePortatil) -and ((Get-NodeMajor $nodePortatil) -ge 14)
  if (-not $nodePortatilOk) {
    Write-Aviso ' Node.js no encontrado o es demasiado antiguo.'
    Write-Paso ' Se descargará Node.js portátil solo para compilar (primera vez).'
    $version = 'v22.12.0'
    try {
      $indice = Invoke-RestMethod -Uri 'https://nodejs.org/dist/index.json' -UseBasicParsing -TimeoutSec 30
      $rel = $indice | Where-Object { $_.version -like 'v22.*' } | Select-Object -First 1
      if ($rel) { $version = $rel.version }
    } catch {}
    $zip = Join-Path $env:TEMP ('cv_node_' + [guid]::NewGuid().ToString('N') + '.zip')
    try {
      $url = "https://nodejs.org/dist/$version/node-$version-win-x64.zip"
      Invoke-Descarga -Url $url -Destino $zip -Etiqueta "Node.js $version portátil"
      Expandir-Paquete -Zip $zip -Destino $dirNode -ExeRelativo 'node.exe'
    } finally {
      Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    }
  }
  $env:PATH = "$dirNode;$env:PATH"
  return @{ Node = (Join-Path $dirNode 'node.exe'); Npm = (Join-Path $dirNode 'npm.cmd') }
}

function Get-ModoBuildGuardado([string]$Defecto = 'prod') {
  $dir = Join-Path $env:LOCALAPPDATA 'CanalVideos'
  $archivo = Join-Path $dir 'build-mode.txt'
  try {
    if (Test-Path -LiteralPath $archivo) {
      $valor = (Get-Content -LiteralPath $archivo -Raw -ErrorAction Stop).Trim()
      if ($valor -eq 'prod' -or $valor -eq 'dev') { return $valor }
    }
  } catch {}
  return $Defecto
}

function Set-ModoBuildGuardado([string]$Modo) {
  if ($Modo -ne 'prod' -and $Modo -ne 'dev') { return }
  $dir = Join-Path $env:LOCALAPPDATA 'CanalVideos'
  try {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $dir 'build-mode.txt') -Value $Modo -Encoding ASCII
  } catch {}
}

function Select-ModoBuild([string]$Defecto = 'prod', [int]$Segundos = 5) {
  if ($Segundos -lt 1) { $Segundos = 1 }
  $etiqueta = if ($Defecto -eq 'dev') { '2) Desarrollo' } else { '1) Producción' }
  Write-Host ''
  Write-Host '  ¿Cómo quieres compilar la aplicación?'
  Write-Host '    [1] Producción   optimizado; es lo que se usa a diario'
  Write-Host '    [2] Desarrollo   build rápido y con mapas de código'
  Write-Host ("  Se usará {0} en {1} s (pulsa 1 o 2 para cambiar; Enter para elegir ya)..." -f $etiqueta, $Segundos) -ForegroundColor DarkGray

  $interactivo = $false
  try { $interactivo = -not [Console]::IsInputRedirected } catch {}
  $fin = (Get-Date).AddSeconds($Segundos)
  while ((Get-Date) -lt $fin) {
    $restante = [int][Math]::Ceiling(($fin - (Get-Date)).TotalSeconds)
    if ($restante -lt 0) { $restante = 0 }
    Write-Host ("`r  Auto-selección en {0} s...   " -f $restante) -NoNewline
    if ($interactivo) {
      try {
        if ([Console]::KeyAvailable) {
          $tecla = [Console]::ReadKey($true)
          Write-Host ''
          if ($tecla.KeyChar -eq '1') { return 'prod' }
          if ($tecla.KeyChar -eq '2') { return 'dev' }
          if ($tecla.Key -eq 'Enter') { return $Defecto }
        }
      } catch { $interactivo = $false }
    }
    Start-Sleep -Milliseconds 100
  }
  Write-Host ''
  return $Defecto
}

function Stop-CanalVideosApp {
  $detenidos = @()

  # Nunca detener este proceso ni sus padres (el propio script de build/test).
  $excluir = @([int]$PID)
  try {
    $actual = Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction SilentlyContinue
    while ($actual -and $actual.ParentProcessId) {
      $padre = [int]$actual.ParentProcessId
      if ($excluir -contains $padre) { break }
      $excluir += $padre
      $actual = Get-CimInstance Win32_Process -Filter "ProcessId=$padre" -ErrorAction SilentlyContinue
    }
  } catch {}

  $perfil = Join-Path $script:CanalVideosRoot '.chrome-profile'
  try {
    $procesos = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
      $excluir -notcontains [int]$_.ProcessId -and (
        ($_.Name -eq 'java.exe' -and $_.CommandLine -like "*$($script:CanalVideosJarName)*") -or
        ($_.Name -eq 'powershell.exe' -and $_.CommandLine -like '*-File*Launch-CanalVideos.ps1*') -or
        (($_.Name -eq 'msedge.exe' -or $_.Name -eq 'chrome.exe') -and
          $_.CommandLine -like "*$perfil*" -and $_.CommandLine -like '*--app=http://localhost*')
      )
    }
    foreach ($p in @($procesos)) {
      try {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
        if ($detenidos -notcontains $p.ProcessId) { $detenidos += $p.ProcessId }
      } catch {}
    }
  } catch {}

  if ($detenidos.Count -gt 0) {
    Write-Aviso (' Cerrando la app en ejecución ({0} proceso(s))...' -f $detenidos.Count)
    Start-Sleep -Milliseconds 1500
  }
  return $detenidos
}

function Ensure-ConfigDb {
  $dir = Join-Path $script:CanalVideosRoot 'java_backend'
  $archivo = Join-Path $dir 'config-db.yaml'
  if (Test-Path -LiteralPath $archivo) { return $archivo }

  $schema = Join-Path $script:CanalVideosRoot 'database\schema-sqlite.sql'
  if (-not (Test-Path -LiteralPath $schema)) {
    throw 'Falta database\schema-sqlite.sql. Descarga el repositorio completo para crear la base SQLite.'
  }
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  # Instalación nueva: SQLite (sin servidor) para que la app funcione sin instalar nada.
  $db = Join-Path $script:CanalVideosRoot 'database\canal_videos_telegram.db'
  New-Item -ItemType Directory -Path (Split-Path $db -Parent) -Force | Out-Null
  $dbYaml = ($db -replace '\\', '/')
  $dbUrl = 'jdbc:sqlite:' + $dbYaml + '?journal_mode=WAL&foreign_keys=on&busy_timeout=5000&date_class=text'
  $yaml = @"
# ============================================================
#  Conexión a la base de datos (generado automáticamente)
# ============================================================
#  Instalación nueva: SQLite, un único archivo y sin servidor.
#  Puedes cambiarla desde Configuración -> Datos.
#  NO subir a git.
# ============================================================

spring:
  datasource:
    url: "$dbUrl"
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      minimum-idle: 0
      connection-timeout: 1500
      initialization-fail-timeout: -1
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.community.dialect.SQLiteDialect

app:
  bootstrap-bd:
    enabled: true
    motor: sqlite
    archivo: "$dbYaml"
    nombre-base: "canal_videos_telegram"
"@
  [IO.File]::WriteAllText($archivo, $yaml, (New-Object System.Text.UTF8Encoding($false)))
  Write-Aviso ' Primera ejecución: se creó java_backend\config-db.yaml (SQLite).'
  return $archivo
}

function Ensure-ClaveMaestra {
  $dir = Join-Path $script:CanalVideosRoot 'java_backend'
  $archivo = Join-Path $dir 'clave_maestra.bin'
  if (-not (Test-Path -LiteralPath $archivo)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllBytes($archivo, $bytes)
    Write-Aviso ' Se creó java_backend\clave_maestra.bin (guárdalo: cifra la configuración).'
  }
  return $archivo
}

function Test-IconoArchivo([string]$RutaRelativa) {
  $ruta = Join-Path $script:CanalVideosRoot $RutaRelativa
  if (-not (Test-Path -LiteralPath $ruta -PathType Leaf)) { return $false }
  try { return ((Get-Item -LiteralPath $ruta).Length -gt 0) } catch { return $false }
}

function Get-IconosEstado {
  $faltantes = @()
  foreach ($rel in @($script:IconosFuente + @($script:IconosDerivados.Keys))) {
    if (-not (Test-IconoArchivo $rel)) { $faltantes += $rel }
  }
  return [pscustomobject]@{
    Ok = ($faltantes.Count -eq 0)
    Faltantes = $faltantes
    Detalle = if ($faltantes.Count -eq 0) { 'Completos' } else { 'Faltan: ' + ($faltantes -join ', ') }
  }
}

function Ensure-IconosCanalVideos {
  $inicial = Get-IconosEstado
  if ($inicial.Ok) {
    return [pscustomobject]@{ Ok = $true; Regenerado = $false; Restaurados = @(); Faltantes = @(); Detalle = 'Completos' }
  }

  $faltanFuente = @($script:IconosFuente | Where-Object { -not (Test-IconoArchivo $_) })
  $generado = $false
  $errorPreparacion = $null

  if ($faltanFuente.Count -gt 0) {
    $generador = Join-Path $script:CanalVideosRoot 'generate-icons.ps1'
    if (-not (Test-Path -LiteralPath $generador)) {
      $errorPreparacion = 'No se encontró generate-icons.ps1 para regenerar los íconos.'
    } else {
      try {
        $psExe = Join-Path $PSHOME 'powershell.exe'
        if (-not (Test-Path -LiteralPath $psExe)) { $psExe = 'powershell.exe' }
        & $psExe -NoProfile -ExecutionPolicy Bypass -File $generador *> $null
        if ($LASTEXITCODE -ne 0) { throw "generate-icons.ps1 terminó con código $LASTEXITCODE." }
        $generado = $true
      } catch {
        $errorPreparacion = $_.Exception.Message
      }
    }
  }

  $restaurados = @()
  foreach ($destino in @($script:IconosDerivados.Keys)) {
    if (Test-IconoArchivo $destino) { continue }
    $origen = $script:IconosDerivados[$destino]
    if (-not (Test-IconoArchivo $origen)) { continue }
    try {
      $rutaDestino = Join-Path $script:CanalVideosRoot $destino
      $dirDestino = Split-Path -Parent $rutaDestino
      if (-not (Test-Path -LiteralPath $dirDestino)) {
        New-Item -ItemType Directory -Path $dirDestino -Force | Out-Null
      }
      Copy-Item -LiteralPath (Join-Path $script:CanalVideosRoot $origen) -Destination $rutaDestino -Force -ErrorAction Stop
      $restaurados += $destino
    } catch {
      if (-not $errorPreparacion) { $errorPreparacion = $_.Exception.Message }
    }
  }

  $final = Get-IconosEstado
  $detalle = if ($final.Ok) {
    if ($generado) { 'Regenerados con generate-icons.ps1.' }
    elseif ($restaurados.Count -gt 0) { 'Restaurados desde los íconos existentes.' }
    else { 'Completos.' }
  } elseif ($errorPreparacion) { $errorPreparacion }
  else { $final.Detalle }

  return [pscustomobject]@{
    Ok = $final.Ok
    Regenerado = $generado
    Restaurados = $restaurados
    Faltantes = $final.Faltantes
    Detalle = $detalle
  }
}

function Get-JarPath {
  $candidatos = @(
    (Join-Path $script:CanalVideosRoot "java_backend\target\$($script:CanalVideosJarName)"),
    (Join-Path $script:CanalVideosRoot "app\$($script:CanalVideosJarName)"),
    (Join-Path $script:CanalVideosRoot $script:CanalVideosJarName)
  )
  foreach ($c in $candidatos) {
    if (Test-Path -LiteralPath $c) { return $c }
  }
  return $null
}

function Write-Titulo([string]$Texto) {
  Write-Host ''
  Write-Host ('=' * 66) -ForegroundColor DarkCyan
  Write-Host ("  $Texto") -ForegroundColor Cyan
  Write-Host ('=' * 66) -ForegroundColor DarkCyan
}

function Write-Seccion([string]$Texto) {
  Write-Host ''
  $linea = '  ' + $Texto + '  ' + ('-' * [Math]::Max(0, 56 - $Texto.Length))
  Write-Host $linea -ForegroundColor Cyan
}

function Write-Check([string]$Nombre, [bool]$Ok, [string]$Detalle = '', [string]$Accion = '') {
  $tag = if ($Ok) { '[ OK ]' } else { '[FALTA]' }
  $color = if ($Ok) { 'Green' } else { 'Yellow' }
  Write-Host ("  {0} {1}" -f $tag, $Nombre) -ForegroundColor $color -NoNewline
  if ($Detalle) { Write-Host (" - {0}" -f $Detalle) -ForegroundColor Gray } else { Write-Host '' }
  if (-not $Ok -and $Accion) { Write-Host ("         -> {0}" -f $Accion) -ForegroundColor DarkYellow }
}

function Test-Tcp([string]$Servidor, [int]$Puerto, [int]$Ms = 800) {
  $c = New-Object Net.Sockets.TcpClient
  try {
    $iar = $c.BeginConnect($Servidor, $Puerto, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($Ms)) { return $false }
    $c.EndConnect($iar)
    return $true
  } catch { return $false } finally { $c.Close() }
}

function Test-PuertoLibre([int]$Puerto) {
  return (-not (Test-Tcp '127.0.0.1' $Puerto))
}

function Get-ProcesoPuerto([int]$Puerto) {
  try {
    $conn = Get-NetTCPConnection -LocalPort $Puerto -State Listen -ErrorAction Stop | Select-Object -First 1
    if ($conn) {
      $p = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
      return [pscustomobject]@{
        Pid = $conn.OwningProcess
        Nombre = if ($p) { $p.ProcessName } else { 'desconocido' }
        Ruta = if ($p) { $p.Path } else { '' }
      }
    }
  } catch {
    try {
      $linea = netstat -ano | Select-String -Pattern (":{0}\s+.*LISTENING" -f $Puerto) | Select-Object -First 1
      if ($linea -and $linea.Line -match '\s+(\d+)\s*$') {
        $procId = [int]$Matches[1]
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        return [pscustomobject]@{
          Pid = $procId
          Nombre = if ($p) { $p.ProcessName } else { 'desconocido' }
          Ruta = if ($p) { $p.Path } else { '' }
        }
      }
    } catch {}
  }
  return $null
}

function Test-EsWindowsX64 {
  return ([Environment]::Is64BitOperatingSystem -and $env:PROCESSOR_ARCHITECTURE -match 'AMD64|ARM64')
}

function Test-PermisosEscritura([string]$Directorio) {
  try {
    $prueba = Join-Path $Directorio ('.cv_escritura_' + [guid]::NewGuid().ToString('N') + '.tmp')
    [IO.File]::WriteAllText($prueba, 'ok')
    Remove-Item -LiteralPath $prueba -Force
    return $true
  } catch { return $false }
}

function Test-EspacioLibre([string]$Directorio, [double]$MinGB = 1.5) {
  try {
    $raiz = [IO.Path]::GetPathRoot((Resolve-Path -LiteralPath $Directorio).Path)
    $letra = $raiz.Substring(0, 1)
    $drive = Get-PSDrive -Name $letra -ErrorAction SilentlyContinue
    if (-not $drive) { return $true }
    return (($drive.Free / 1GB) -ge $MinGB)
  } catch { return $true }
}

function Test-Internet([int]$TimeOutSeg = 8) {
  foreach ($url in @('https://nodejs.org/dist/index.json', 'https://api.adoptium.net/v3/info/available_releases')) {
    try {
      $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $TimeOutSeg -ErrorAction Stop
      if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true }
    } catch {
      try {
        $resp = $_.Exception.Response
        if ($resp) { return $true }
      } catch {}
    }
  }
  return $false
}

function Get-VCRedistEstado {
  $sys = Join-Path $env:SystemRoot $(if ([Environment]::Is64BitProcess) { 'System32' } else { 'Sysnative' })
  $requeridos = @('vcruntime140.dll', 'msvcp140.dll')
  $faltantes = @()
  foreach ($dll in $requeridos) {
    if (-not (Test-Path -LiteralPath (Join-Path $sys $dll))) { $faltantes += $dll }
  }

  $claves = @(
    'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64'
  )
  $registrado = $false
  $version = $null
  foreach ($clave in $claves) {
    try {
      $props = Get-ItemProperty -LiteralPath $clave -ErrorAction Stop
      if ($props -and ("$($props.Installed)" -eq '1')) {
        $registrado = $true
        if ($props.Version) { $version = ([string]$props.Version).Trim() -replace '^[vV]', '' }
        break
      }
    } catch {}
  }

  $ok = ($faltantes.Count -eq 0)
  if ($ok -and $registrado) {
    $detalle = if ($version) { "Registrado (versión $version)" } else { 'Registrado en Windows' }
    $fuente = 'Registro y System32'
  } elseif ($ok) {
    $detalle = 'DLLs presentes en System32 (versión no registrada)'
    $fuente = 'System32'
  } elseif ($registrado) {
    $detalle = ('Registrado, pero faltan: {0}' -f ($faltantes -join ', '))
    $fuente = 'Registro'
  } else {
    $detalle = 'No se encontró vcruntime140.dll ni el redistribuidor x64'
    $fuente = 'Ninguna'
  }

  return [pscustomobject]@{
    Ok = $ok
    Registrado = $registrado
    Version = $version
    Faltantes = $faltantes
    Fuente = $fuente
    Detalle = $detalle
    Dlls = $sys
  }
}

function Test-FirmaMicrosoft([string]$Ruta) {
  if (-not $Ruta -or -not (Test-Path -LiteralPath $Ruta)) { return $false }
  try {
    $firma = Get-AuthenticodeSignature -LiteralPath $Ruta -ErrorAction Stop
    if ($firma.Status -ne 'Valid') { return $false }
    return ([string]$firma.SignerCertificate.Subject -match 'Microsoft')
  } catch { return $false }
}

function Test-JarValido([string]$Ruta) {
  if (-not $Ruta -or -not (Test-Path -LiteralPath $Ruta)) { return $false }
  $item = Get-Item -LiteralPath $Ruta -ErrorAction SilentlyContinue
  if (-not $item -or $item.Length -lt 5MB) { return $false }
  try {
    $fs = [IO.File]::OpenRead($Ruta)
    try {
      $b = New-Object byte[] 2
      [void]$fs.Read($b, 0, 2)
      return ($b[0] -eq 0x50 -and $b[1] -eq 0x4B)
    } finally { $fs.Close() }
  } catch { return $false }
}

function Get-ArchivosFuenteRecientes {
  $rutas = @(
    (Join-Path $script:CanalVideosRoot 'java_backend\src'),
    (Join-Path $script:CanalVideosRoot 'angular_frontend\src')
  )
  $archivos = @()
  foreach ($r in $rutas) {
    if (Test-Path -LiteralPath $r) {
      $archivos += @(Get-ChildItem -LiteralPath $r -Recurse -File -ErrorAction SilentlyContinue)
    }
  }
  foreach ($f in @('java_backend\pom.xml', 'angular_frontend\angular.json', 'angular_frontend\package.json', 'angular_frontend\package-lock.json')) {
    $p = Join-Path $script:CanalVideosRoot $f
    if (Test-Path -LiteralPath $p) { $archivos += Get-Item -LiteralPath $p }
  }
  if (-not $archivos) { return $null }
  return ($archivos | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}

function Get-JarEstado {
  $jar = Get-JarPath
  if (-not $jar) {
    return [pscustomobject]@{ Estado = 'missing'; Jar = $null; JarFecha = $null; Fuente = $null; FuenteFecha = $null }
  }
  $item = Get-Item -LiteralPath $jar -ErrorAction SilentlyContinue
  if (-not (Test-JarValido $jar)) {
    return [pscustomobject]@{ Estado = 'invalid'; Jar = $jar; JarFecha = $item.LastWriteTime; Fuente = $null; FuenteFecha = $null }
  }
  $fuente = Get-ArchivosFuenteRecientes
  $desactualizado = ($fuente -and $fuente.LastWriteTime -gt $item.LastWriteTime.AddSeconds(3))
  return [pscustomobject]@{
    Estado = if ($desactualizado) { 'stale' } else { 'ok' }
    Jar = $jar
    JarFecha = $item.LastWriteTime
    Fuente = if ($fuente) { $fuente.FullName } else { $null }
    FuenteFecha = if ($fuente) { $fuente.LastWriteTime } else { $null }
  }
}

function Get-ConfigDbResumen {
  $ruta = Join-Path $script:CanalVideosRoot 'java_backend\config-db.yaml'
  if (-not (Test-Path -LiteralPath $ruta)) {
    $schemaOk = Test-Path -LiteralPath (Join-Path $script:CanalVideosRoot 'database\schema-sqlite.sql')
    return [pscustomobject]@{
      Existe = $false; Ok = $schemaOk; Motor = 'sqlite'; Url = ''
      Archivo = (Join-Path $script:CanalVideosRoot 'database\canal_videos_telegram.db')
      Detalle = if ($schemaOk) { 'Se creará automáticamente (SQLite)' } else { 'Falta database\schema-sqlite.sql para crear SQLite' }
    }
  }
  $texto = ''
  try { $texto = Get-Content -LiteralPath $ruta -Raw -ErrorAction Stop } catch {}
  $motor = 'mariadb'
  if ($texto -match '(?m)^\s*motor:\s*"?([A-Za-z0-9_-]+)"?') { $motor = $Matches[1].ToLowerInvariant() }
  $url = ''
  if ($texto -match '(?m)^\s*url:\s*"?([^"\r\n]+)"?') { $url = $Matches[1].Trim() }
  if ($url -match 'jdbc:sqlite:') { $motor = 'sqlite' }
  $archivo = ''
  if ($texto -match '(?m)^\s*archivo:\s*"?([^"\r\n]+)"?') { $archivo = $Matches[1].Trim() }
  if ($motor -eq 'sqlite' -and $url -match 'jdbc:sqlite:([^?]+)') { $archivo = $Matches[1] }
  $ok = ($texto -match '(?m)^\s*spring:') -and ($texto -match '(?m)^\s*datasource:') -and [bool]$url
  $detalle = ''
  if ($motor -eq 'sqlite') {
    if (-not $archivo) { $ok = $false; $detalle = 'SQLite sin ruta de archivo' }
    else {
      $rutaDb = if ([IO.Path]::IsPathRooted($archivo)) { $archivo } else { Join-Path $script:CanalVideosRoot $archivo }
      if (Test-Path -LiteralPath $rutaDb) { $detalle = "SQLite: $rutaDb" }
      elseif (Test-Path -LiteralPath (Join-Path $script:CanalVideosRoot 'database\schema-sqlite.sql')) { $detalle = "SQLite: se creará $rutaDb" }
      else { $ok = $false; $detalle = 'SQLite sin esquema: falta database\schema-sqlite.sql' }
    }
  } else {
    $hostDb = ''; $puertoDb = 3306; $baseDb = ''
    if ($url -match '//([^/:]+)(?::(\d+))?/([^?]+)') {
      $hostDb = $Matches[1]
      if ($Matches[2]) { $puertoDb = [int]$Matches[2] }
      $baseDb = $Matches[3]
    }
    $detalle = ("{0}: {1}:{2}/{3}" -f $motor, $hostDb, $puertoDb, $baseDb)
  }
  if (-not $ok -and -not $url) {
    $detalle = 'Falta spring.datasource.url o el archivo no tiene el formato esperado.'
  }
  return [pscustomobject]@{
    Existe = $true; Ok = $ok; Motor = $motor; Url = $url; Archivo = $archivo
    Detalle = $detalle
  }
}

function Get-AppProceso {
  try {
    return (Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop |
      Where-Object { $_.CommandLine -and $_.CommandLine -like "*$($script:CanalVideosJarName)*" } |
      Select-Object -First 1)
  } catch { return $null }
}

function Test-AppResponde([int]$Puerto = 9000, [int]$TimeOutSeg = 2) {
  try {
    $r = Invoke-WebRequest -Uri ("http://localhost:{0}/api/titulos" -f $Puerto) -UseBasicParsing -TimeoutSec $TimeOutSeg -ErrorAction Stop
    return ($r.StatusCode -eq 200)
  } catch { return $false }
}

function Start-AppDetached([int]$Puerto = 9000, [string]$JarPath = '') {
  $launcher = Join-Path $script:CanalVideosRoot 'Launch-CanalVideos.ps1'
  if (-not (Test-Path -LiteralPath $launcher)) { throw 'No se encontró Launch-CanalVideos.ps1.' }
  $psExe = Join-Path $PSHOME 'powershell.exe'
  if (-not (Test-Path -LiteralPath $psExe)) { $psExe = 'powershell.exe' }
  $argumentos = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Port {1}' -f $launcher, $Puerto
  if ($JarPath) { $argumentos += ' -JarPath "{0}"' -f $JarPath }
  $proc = Start-Process -FilePath $psExe -ArgumentList $argumentos -WorkingDirectory $script:CanalVideosRoot -WindowStyle Hidden -PassThru
  return $proc
}

function Wait-AppReady([int]$Puerto = 9000, [int]$Segundos = 90, [switch]$Silencioso, [object]$Launcher = $null) {
  $inicio = Get-Date
  $fin = $inicio.AddSeconds($Segundos)
  $punto = 0
  while ((Get-Date) -lt $fin) {
    if (Test-AppResponde -Puerto $Puerto -TimeOutSeg 2) {
      if (-not $Silencioso) { Write-Host '' }
      return $true
    }
    if (-not $Silencioso) {
      $punto = ($punto % 3) + 1
      Write-Host ("`r  Abriendo la aplicación{0}   " -f ('.' * $punto)) -NoNewline -ForegroundColor Cyan
    }
    if ($Launcher) {
      try {
        if ($Launcher.HasExited) {
          if (-not $Silencioso) { Write-Host '' }
          return $false
        }
      } catch {}
    } elseif (((Get-Date) - $inicio).TotalSeconds -gt 30 -and -not (Get-AppProceso)) {
      if (-not $Silencioso) { Write-Host '' }
      return $false
    }
    Start-Sleep -Milliseconds 700
  }
  if (-not $Silencioso) { Write-Host '' }
  return $false
}

function Get-BackendSugerencia([string]$LogErr) {
  $texto = ''
  if ($LogErr -and (Test-Path -LiteralPath $LogErr)) {
    try { $texto = (Get-Content -LiteralPath $LogErr -Tail 60 -ErrorAction Stop) -join "`n" } catch {}
  }
  if ($texto -match 'Access denied for user') { return 'La base de datos rechazó las credenciales. Abre la app y revisa Configuración -> Datos.' }
  if ($texto -match 'Unknown database|does not exist') { return 'La base de datos no existe. Créala desde Configuración -> Datos o usa SQLite.' }
  if ($texto -match 'Address already in use|Port .* was already in use') { return 'El puerto está ocupado. Cierra la otra instancia o inicia con otro puerto.' }
  if ($texto -match 'UnsupportedClassVersion|Unsupported major.minor') { return 'La versión de Java es antigua. Vuelve a iniciar para descargar Java 17.' }
  if ($texto -match 'VCRUNTIME140|MSVCP140|0xc0000135|-1073741515') { return 'Falta Visual C++ 2015-2022 x64. Ejecuta AsistenteDeCompilacion-CanalVideos.bat y elige [5].' }
  if ($texto -match 'no such table') { return 'Faltan tablas en la base. Abre Configuración -> Datos y aplica el esquema.' }
  if ($texto -match 'Connection refused|Communications link failure') { return 'No se pudo conectar al servidor de base de datos. Comprueba que esté encendido.' }
  if ($texto -match 'Could not resolve placeholder|Error creating bean|Failed to bind properties') { return 'La configuración de la base no es válida. Revisa Configuración -> Datos.' }
  return 'Revisa el detalle en el archivo de log indicado.'
}

function Show-BackendFailure([string]$LogErr, [string]$LogOut) {
  Write-Host ''
  Write-Host '  La aplicación no llegó a estar lista.' -ForegroundColor Red
  if ($LogErr -and (Test-Path -LiteralPath $LogErr)) {
    Write-Host ''
    Write-Host '  Últimas líneas del error:' -ForegroundColor Yellow
    try {
      Get-Content -LiteralPath $LogErr -Tail 12 -ErrorAction Stop | ForEach-Object {
        Write-Host ("    {0}" -f $_) -ForegroundColor DarkGray
      }
    } catch {}
  }
  Write-Host ''
  Write-Host ("  Qué puedes hacer: {0}" -f (Get-BackendSugerencia $LogErr)) -ForegroundColor Yellow
  if ($LogOut) { Write-Host ("  Log completo: {0}" -f $LogOut) -ForegroundColor DarkGray }
}

function Get-HerramientasEstado {
  $java = Get-JavaLocal -Silencioso
  $jdk = Get-JdkLocal -Silencioso
  $node = Get-NodeInfoLocal -Silencioso
  return [pscustomobject]@{
    Java = $java
    JavaVersion = if ($java) { Get-JavaMajor $java } else { 0 }
    Jdk = $jdk
    JdkVersion = if ($jdk) { Get-JavacMajor (Join-Path $jdk 'bin\javac.exe') } else { 0 }
    Node = if ($node) { $node.Node } else { $null }
    NodeVersion = if ($node) { Get-NodeMajor $node.Node } else { 0 }
    Npm = if ($node) { $node.Npm } else { $null }
    MavenWrapper = (Test-Path -LiteralPath (Join-Path $script:CanalVideosRoot 'java_backend\mvnw.cmd'))
    NodeModules = (Test-Path -LiteralPath (Join-Path $script:CanalVideosRoot 'angular_frontend\node_modules\@angular\cli'))
  }
}

function Find-PuertoLibre([int]$Desde = 9000, [int]$Hasta = 9010) {
  for ($p = $Desde; $p -le $Hasta; $p++) {
    if (Test-PuertoLibre $p) { return $p }
  }
  return 0
}
