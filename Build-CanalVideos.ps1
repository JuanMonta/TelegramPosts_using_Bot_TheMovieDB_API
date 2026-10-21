<#
.SYNOPSIS
  Build-CanalVideos.ps1 -- compila el frontend Angular + empaqueta el jar (desde RAIZ).
  Prepara solo las herramientas que falten (Node, JDK, Maven) sin instalarlas en el sistema.
.EXAMPLE
  .\Build-CanalVideos.ps1                 # menú Producción/Desarrollo (5 s, recuerda la última)
  .\Build-CanalVideos.ps1 -Modo dev       # sin menú: build de desarrollo
  .\Build-CanalVideos.ps1 -SkipFrontend   # solo empaqueta el jar
#>
param(
  [ValidateSet('prod', 'dev')][string]$Modo,
  [switch]$SkipFrontend,
  [switch]$SinMenu
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'CanalVideos.Tools.ps1')

$JarName = 'crow-0.0.1-SNAPSHOT.jar'

try {
  # --- Directorios ---
  $ngDir = Join-Path $root 'angular_frontend'
  if (-not (Test-Path -LiteralPath (Join-Path $ngDir 'angular.json'))) { $ngDir = $root }
  if (-not (Test-Path -LiteralPath (Join-Path $ngDir 'angular.json'))) { throw 'No se encontró angular.json. Ejecuta el script desde la raíz del proyecto.' }
  $mvnDir = Join-Path $root 'java_backend'
  if (-not (Test-Path -LiteralPath (Join-Path $mvnDir 'pom.xml'))) { $mvnDir = Join-Path (Split-Path $ngDir -Parent) 'java_backend' }
  if (-not (Test-Path -LiteralPath (Join-Path $mvnDir 'pom.xml'))) { throw 'No se encontró pom.xml (java_backend).' }
  $dist = Join-Path $ngDir 'dist\CanalVideos'
  $requiereFrontend = -not $SkipFrontend

  Write-Titulo 'CanalStudio - Compilación'
  Write-Host '  Elige el tipo de compilación. Después se revisará el entorno.' -ForegroundColor Gray

  # --- Modo de compilación (memoria + menú con cuenta atrás) ---
  if (-not $Modo) {
    $ultimo = Get-ModoBuildGuardado 'prod'
    if ($SinMenu -or $SkipFrontend) {
      $Modo = $ultimo
    } else {
      $Modo = Select-ModoBuild $ultimo 5
    }
  }
  if (-not $SkipFrontend) { Set-ModoBuildGuardado $Modo }
  $modoTexto = if ($Modo -eq 'dev') { 'Desarrollo' } else { 'Producción' }

  Write-Host (" Modo    : {0}" -f $modoTexto) -ForegroundColor Cyan
  Write-Host (" Frontend: {0}" -f $ngDir) -ForegroundColor DarkGray
  Write-Host (" Backend : {0}" -f $mvnDir) -ForegroundColor DarkGray

  # --- Comprobaciones previas: evitar fallos crípticos a mitad del build ---
  Write-Seccion 'Comprobando entorno'
  $critico = $true
  if (-not (Test-EsWindowsX64)) { Write-Check 'Windows de 64 bits' $false 'Necesario para las herramientas portátiles.'; $critico = $false }
  else { Write-Check 'Windows de 64 bits' $true }

  if (-not (Test-PermisosEscritura $root)) { Write-Check 'Permisos de escritura' $false 'La carpeta del proyecto no permite escribir.' 'Mueve el proyecto a una carpeta tuya (por ejemplo, Documentos).'; $critico = $false }
  else { Write-Check 'Permisos de escritura' $true }

  if (-not (Test-EspacioLibre $root 1.5)) { Write-Check 'Espacio en disco' $false 'Se recomiendan al menos 1,5 GB libres.' 'Libera espacio y vuelve a intentarlo.'; $critico = $false }
  else { Write-Check 'Espacio en disco (1,5 GB)' $true }

  $jdkLocal = Get-JdkLocal -Silencioso
  $nodeLocal = if ($requiereFrontend) { Get-NodeInfoLocal -Silencioso } else { $null }
  $depsLocal = Test-Path -LiteralPath (Join-Path $ngDir 'node_modules\@angular\cli')
  $mvnw = Join-Path $mvnDir 'mvnw.cmd'
  $mvnwOk = Test-Path -LiteralPath $mvnw
  $mvnOk = [bool](Get-Command 'mvn' -ErrorAction SilentlyContinue)

  $mavenCache = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
  $mavenCacheOk = Test-Path -LiteralPath $mavenCache
  $faltanHerramientas = (-not $jdkLocal) -or ($requiereFrontend -and (-not $nodeLocal -or -not $depsLocal))
  if (-not $mvnwOk -and -not $mvnOk) {
    Write-Check 'Maven' $false 'No se encontró mvnw.cmd ni mvn en PATH.' 'Usa una copia completa del repositorio (incluye mvnw.cmd).'
    $critico = $false
  } else {
    $mvnTexto = if ($mvnwOk) { 'mvnw.cmd' } else { 'mvn del sistema' }
    Write-Check 'Maven' $true $mvnTexto
  }

  $vc = Get-VCRedistEstado
  if (-not $vc.Ok) {
    Write-Check 'Visual C++ 2015-2022 x64' $false $vc.Detalle 'Abre AsistenteDeCompilacion-CanalVideos.bat y elige [5].'
    $critico = $false
  } else {
    Write-Check 'Visual C++ 2015-2022 x64' $true $vc.Detalle
  }

  $internet = $null
  if ($faltanHerramientas -or ($mvnwOk -and -not $mvnOk -and -not $mavenCacheOk)) {
    $internet = Test-Internet
  }
  if ($mvnwOk -and -not $mvnOk -and -not $mavenCacheOk) {
    if ($internet -eq $false) {
      Write-Check 'Maven (primera descarga)' $false 'El wrapper necesita internet la primera vez.' 'Conéctate a internet y vuelve a ejecutar.'
      $critico = $false
    } else {
      Write-Aviso ' Maven: el wrapper descargará el motor de compilación (solo la primera vez).'
    }
  }
  if (-not $jdkLocal) {
    if ($internet -eq $false) { Write-Check 'JDK 17+' $false 'No está y no hay conexión para descargarlo.' 'Conéctate a internet y vuelve a ejecutar.'; $critico = $false }
    else { Write-Aviso ' JDK 17+: se descargará automáticamente (190 MB, solo la primera vez).' }
  } else { Write-Check 'JDK 17+' $true $jdkLocal }
  if ($requiereFrontend) {
    if (-not $nodeLocal) {
      if ($internet -eq $false) { Write-Check 'Node.js' $false 'No está y no hay conexión para descargarlo.' 'Conéctate a internet y vuelve a ejecutar.'; $critico = $false }
      else { Write-Aviso ' Node.js: se descargará automáticamente (solo la primera vez).' }
    } else { Write-Check 'Node.js' $true ("v{0} - {1}" -f (Get-NodeMajor $nodeLocal.Node), $nodeLocal.Node) }
    if (-not $depsLocal) {
      if ($internet -eq $false) { Write-Check 'Dependencias del frontend' $false 'Falta node_modules y no hay conexión.' 'Conéctate a internet y vuelve a ejecutar.'; $critico = $false }
      else { Write-Aviso ' Dependencias del frontend: se instalarán con npm (puede tardar unos minutos).' }
    } else { Write-Check 'Dependencias del frontend' $true 'node_modules' }
  }
  if (-not $critico) { throw 'No se cumplen los requisitos mínimos para compilar.' }

  # --- La app no puede seguir abierta: el jar y la carpeta target están en uso ---
  Write-Seccion 'Preparando la compilación'
  [void](Stop-CanalVideosApp)

  $iconos = Ensure-IconosCanalVideos
  if (-not $iconos.Ok) {
    Write-Aviso (" No se pudieron preparar los íconos: {0}" -f $iconos.Detalle)
    if ($iconos.Faltantes.Count -gt 0) {
      Write-Host (" Faltan: {0}" -f ($iconos.Faltantes -join ', ')) -ForegroundColor DarkGray
    }
  } elseif ($iconos.Regenerado) {
    Write-Ok ' Íconos regenerados.'
    if ($SkipFrontend) { Write-Aviso ' Frontend omitido: los íconos se aplicarán en la próxima compilación completa.' }
  } elseif ($iconos.Restaurados.Count -gt 0) {
    Write-Ok (" Íconos restaurados: {0}." -f ($iconos.Restaurados -join ', '))
  }

  # --- Herramientas portátiles ---
  $psDir = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0'
  if ((Test-Path -LiteralPath $psDir) -and ($env:PATH -notlike "*$psDir*")) { $env:PATH = "$psDir;$env:PATH" }
  $javaHome = Resolve-Jdk
  $env:JAVA_HOME = $javaHome
  $env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"
  Write-Host (" JDK     : {0}" -f $javaHome) -ForegroundColor DarkGray

  $npm = $null
  if ($requiereFrontend) {
    $nodeInfo = Resolve-Node
    $npm = $nodeInfo.Npm
    Write-Host (" Node    : {0}" -f $nodeInfo.Node) -ForegroundColor DarkGray
    if (-not (Test-Path -LiteralPath (Join-Path $ngDir 'node_modules\@angular\cli'))) {
      Write-Paso '[0/2] Instalando dependencias del frontend (primera vez)...'
      Push-Location $ngDir
      try {
        $lock = Join-Path $ngDir 'package-lock.json'
        if (Test-Path -LiteralPath $lock) {
          & $npm 'ci' '--no-audit' '--no-fund'
        } else {
          & $npm 'install' '--no-audit' '--no-fund'
        }
        if ($LASTEXITCODE -ne 0) { throw "npm falló con código $LASTEXITCODE" }
      } finally { Pop-Location }
      if (-not (Test-Path -LiteralPath (Join-Path $ngDir 'node_modules\@angular\cli'))) {
        throw 'npm terminó, pero no se instaló Angular CLI. Borra angular_frontend\node_modules y vuelve a intentarlo.'
      }
      Write-Ok '[OK] Dependencias instaladas'
      Write-Host ''
    }
  }

  if ($requiereFrontend) {
    $config = if ($Modo -eq 'dev') { 'development' } else { 'production' }
    Write-Paso ("[1/2] Compilando Angular ({0})..." -f $config)
    Push-Location $ngDir
    try {
      $majorNode = Get-NodeMajor $nodeInfo.Node
      if ($majorNode -ge 17) {
        $env:NODE_OPTIONS = '--openssl-legacy-provider'
      } else {
        Remove-Item Env:NODE_OPTIONS -ErrorAction SilentlyContinue
      }
      $env:NG_CLI_ANALYTICS = 'false'
      $npx = Join-Path (Split-Path $npm -Parent) 'npx.cmd'
      if (-not (Test-Path -LiteralPath $npx)) { $npx = 'npx.cmd' }
      & $npx 'ng' 'build' '--configuration' $config
      if ($LASTEXITCODE -ne 0) { throw "ng build falló con código $LASTEXITCODE" }
    } finally { Pop-Location }
    if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw "No se generó el build en $dist" }
    Write-Ok "[OK] Frontend -> $dist"
    Write-Host ''
  } else {
    Write-Aviso '[1/2] Frontend omitido (-SkipFrontend)'
  }

  Write-Paso '[2/2] Empaquetando backend (Maven, sin tests)...'
  Push-Location $mvnDir
  try {
    if (Test-Path -LiteralPath $mvnw) {
      & $mvnw '--no-transfer-progress' '-DskipTests' 'clean' 'package'
    } else {
      & 'mvn' '--no-transfer-progress' '-DskipTests' 'clean' 'package'
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven falló con código $LASTEXITCODE" }
  } finally { Pop-Location }

  $jar = Join-Path $mvnDir ("target\{0}" -f $JarName)
  if (-not (Test-Path -LiteralPath $jar)) { throw "No se generó el jar esperado: $jar" }
  if (-not (Test-JarValido $jar)) { throw 'El jar generado está incompleto o dañado. Vuelve a compilar.' }

  Write-Host ''
  Write-Host '========================================================' -ForegroundColor Green
  Write-Host ' COMPILACIÓN OK' -ForegroundColor Green
  Write-Host (" Modo: {0}" -f $modoTexto) -ForegroundColor Cyan
  Write-Host (" Jar : {0}" -f $jar) -ForegroundColor Cyan
  Write-Host (" Dist: {0}" -f $dist) -ForegroundColor Cyan
  Write-Host ' Siguiente: AsistenteDeCompilacion-CanalVideos.bat (recomendado) o Build-And-Launch.bat' -ForegroundColor DarkGray
  Write-Host '========================================================' -ForegroundColor Green
} catch {
  $mensaje = $_.Exception.Message
  Write-Host ''
  Write-Host '========================================================' -ForegroundColor Red
  Write-Host ' NO SE PUDO COMPLETAR LA COMPILACIÓN' -ForegroundColor Red
  Write-Host '========================================================' -ForegroundColor Red
  Write-Host (" Motivo: {0}" -f $mensaje) -ForegroundColor Yellow
  $sugerencia = 'Revisa los mensajes anteriores y vuelve a intentarlo.'
  if ($mensaje -match 'requisitos mínimos') { $sugerencia = 'Corrige los puntos marcados con [FALTA] y ejecuta de nuevo.' }
  elseif ($mensaje -match 'Android|angular.json|pom.xml') { $sugerencia = 'Descarga el repositorio completo y ejecuta el script desde su carpeta raíz.' }
  elseif ($mensaje -match 'npm|Node|node_modules') { $sugerencia = 'Comprueba la conexión a internet; si persiste, borra angular_frontend\node_modules y vuelve a ejecutar.' }
  elseif ($mensaje -match 'Java|JDK|Maven|mvnw') { $sugerencia = 'Comprueba la conexión a internet y que la carpeta tools\ se pueda escribir.' }
  elseif ($mensaje -match 'ng build|frontend|Angular') { $sugerencia = 'Revisa los errores de Angular mostrados arriba; suelen indicar un archivo con sintaxis incorrecta.' }
  Write-Host (" Siguiente paso: {0}" -f $sugerencia) -ForegroundColor Yellow
  Write-Host ''
  exit 1
}
