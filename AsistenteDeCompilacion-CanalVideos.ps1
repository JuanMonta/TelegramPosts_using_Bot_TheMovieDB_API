<#
.SYNOPSIS
  AsistenteDeCompilacion-CanalVideos.ps1 -- asistente de compilación y arranque: revisa, compila si hace falta y abre la app.
.DESCRIPTION
  Interfaz guiada para usuarios. También admite modo automático para pruebas:
    .\AsistenteDeCompilacion-CanalVideos.ps1 -Auto -Modo prod
    .\AsistenteDeCompilacion-CanalVideos.ps1 -Diagnostico
#>
param(
  [switch]$Auto,
  [switch]$Diagnostico,
  [ValidateSet('prod', 'dev')][string]$Modo,
  [int]$Port = 9000
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root
. (Join-Path $root 'CanalVideos.Tools.ps1')

$script:Interactivo = $false
try { $script:Interactivo = -not [Console]::IsInputRedirected } catch {}
$script:Auto = [bool]$Auto
if ($script:Auto) { $env:CV_SIN_UI = '1' }
$script:Puerto = if ($Port -gt 0) { $Port } else { 9000 }
$script:LogDir = Join-Path $root 'logs'

function Guardar-Error([string]$Contexto, [object]$Error) {
  try {
    New-Item -ItemType Directory -Path $script:LogDir -Force | Out-Null
    $archivo = Join-Path $script:LogDir 'guiado-errores.log'
    $texto = "[{0}] {1}`r`n{2}`r`n" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Contexto, ($Error | Out-String)
    Add-Content -LiteralPath $archivo -Value $texto -Encoding UTF8
    return $archivo
  } catch { return $null }
}

function Mostrar-Error([string]$Contexto, [object]$Error) {
  $log = Guardar-Error $Contexto $Error
  Write-Host ''
  Write-Host '  No se pudo completar la acción.' -ForegroundColor Red
  Write-Host ("  {0}" -f $Error.Exception.Message) -ForegroundColor Yellow
  if ($log) { Write-Host ("  Detalle técnico: {0}" -f $log) -ForegroundColor DarkGray }
}

function Pausar {
  if ($script:Interactivo) { [void](Read-Host '  Pulsa Enter para continuar') }
}

function Preguntar-SiNo([string]$Texto, [bool]$Defecto = $true) {
  if (-not $script:Interactivo -or $script:Auto) { return $Defecto }
  $opciones = if ($Defecto) { 'S/n' } else { 's/N' }
  $respuesta = Read-Host ("  {0} [{1}]" -f $Texto, $opciones)
  if (-not $respuesta) { return $Defecto }
  return ($respuesta -match '^(s|si|sí|y|yes)$')
}

function Get-PuertoActivo {
  $candidatos = New-Object System.Collections.Generic.List[int]
  $inst = Join-Path $env:LOCALAPPDATA 'CanalVideos\instance.json'
  if (Test-Path -LiteralPath $inst) {
    try {
      $j = Get-Content -LiteralPath $inst -Raw | ConvertFrom-Json
      if ($j -and $j.port) { $candidatos.Add([int]$j.port) }
    } catch {}
  }
  $proc = Get-AppProceso
  if ($proc -and $proc.CommandLine -match '--server\.port=(\d+)') {
    $p = [int]$Matches[1]
    if (-not $candidatos.Contains($p)) { $candidatos.Add($p) }
  }
  if (-not $candidatos.Contains($script:Puerto)) { $candidatos.Add($script:Puerto) }
  foreach ($p in $candidatos) {
    if (Test-AppResponde -Puerto $p -TimeOutSeg 1) { return $p }
  }
  return $script:Puerto
}

function Obtener-Estado {
  $jar = Get-JarEstado
  $config = Get-ConfigDbResumen
  $tools = Get-HerramientasEstado
  $proceso = Get-AppProceso
  $puerto = Get-PuertoActivo
  $activa = if ($proceso) { Test-AppResponde -Puerto $puerto -TimeOutSeg 2 } else { $false }
  return [pscustomobject]@{
    Jar = $jar
    Config = $config
    Tools = $tools
    Proceso = $proceso
    Puerto = $puerto
    Activa = $activa
    Iconos = (Get-IconosEstado)
    VC = (Get-VCRedistEstado)
    X64 = (Test-EsWindowsX64)
    Escritura = (Test-PermisosEscritura $root)
    Espacio = (Test-EspacioLibre $root 1.5)
  }
}

function Mostrar-Resumen([object]$Estado) {
  Write-Titulo 'CanalVideos - Asistente de compilación y arranque'
  Write-Host '  Esta ventana revisa tu equipo, prepara lo que falte y abre la app.' -ForegroundColor Gray
  Write-Host ("  Proyecto: {0}" -f $root) -ForegroundColor DarkGray

  Write-Seccion 'Estado actual'
  $jar = $Estado.Jar
  switch ($jar.Estado) {
    'ok' { Write-Check 'Aplicación compilada' $true ("{0:dd/MM/yyyy HH:mm}" -f $jar.JarFecha) }
    'stale' {
      Write-Host '  [AVISO] La compilación está desactualizada' -ForegroundColor Yellow
      Write-Host ("          Hay cambios en el código desde {0:dd/MM/yyyy HH:mm}." -f $jar.FuenteFecha) -ForegroundColor Gray
    }
    'invalid' { Write-Check 'Aplicación compilada' $false 'El archivo está incompleto o dañado.' 'Se volverá a compilar.' }
    default { Write-Check 'Aplicación compilada' $false 'Todavía no se ha compilado.' 'Se compilará automáticamente.' }
  }

  if ($Estado.Activa) { Write-Check 'Aplicación abierta' $true ("http://localhost:{0}" -f $Estado.Puerto) }
  else { Write-Host '  [INFO] La aplicación está cerrada.' -ForegroundColor DarkGray }

  if (-not $Estado.Iconos.Ok) {
    Write-Host '  [AVISO] Faltan íconos de la aplicación.' -ForegroundColor Yellow
    Write-Host ("          {0}" -f $Estado.Iconos.Detalle) -ForegroundColor Gray
    Write-Host '          Se regenerarán automáticamente al compilar o abrir.' -ForegroundColor Gray
  }

  if ($Estado.Config.Ok) { Write-Check 'Base de datos' $true $Estado.Config.Detalle }
  else { Write-Check 'Base de datos' $false $Estado.Config.Detalle 'Se pedirá corregirla antes de abrir.' }

  if ($Estado.VC.Ok) {
    Write-Check 'Visual C++ 2015-2022 x64' $true $Estado.VC.Detalle
  } else {
    Write-Host '  [AVISO] Falta Visual C++ 2015-2022 x64 (14.x).' -ForegroundColor Yellow
    Write-Host '          Java puede fallar con "VCRUNTIME140.dll no encontrado".' -ForegroundColor Gray
    Write-Host ("          Requerido: {0}." -f $script:VCRedistNombre) -ForegroundColor Gray
    Write-Host ("          Enlace oficial: {0}" -f $script:VCRedistUrl) -ForegroundColor Cyan
  }

  if ($Estado.Tools.Java) { Write-Check 'Java 17+' $true ("{0} (versión {1})" -f $Estado.Tools.Java, $Estado.Tools.JavaVersion) }
  else { Write-Check 'Java 17+' $false 'No disponible.' 'Se descargará automáticamente si hay internet.' }

  if ($Estado.Tools.Jdk) { Write-Check 'JDK para compilar' $true ("versión {0}" -f $Estado.Tools.JdkVersion) }
  else { Write-Host '  [INFO] JDK para compilar: se descargará solo si hace falta compilar.' -ForegroundColor DarkGray }

  if ($Estado.Tools.Node) { Write-Check 'Node.js para compilar' $true ("v{0}" -f $Estado.Tools.NodeVersion) }
  else { Write-Host '  [INFO] Node.js: se descargará solo si hace falta compilar.' -ForegroundColor DarkGray }

  if (-not $Estado.Tools.NodeModules) { Write-Host '  [INFO] Dependencias del frontend: se instalarán en la primera compilación.' -ForegroundColor DarkGray }
  if (-not $Estado.Tools.MavenWrapper) { Write-Host '  [AVISO] No se encontró java_backend\mvnw.cmd.' -ForegroundColor Yellow }
  if (-not $Estado.Escritura) { Write-Check 'Permisos de escritura' $false 'No se puede escribir en la carpeta del proyecto.' 'Mueve el proyecto a una carpeta tuya.' }
  if (-not $Estado.Espacio) { Write-Check 'Espacio en disco' $false 'Menos de 1,5 GB libres.' 'Libera espacio antes de compilar.' }
}

function Mostrar-Diagnostico([object]$Estado) {
  Write-Seccion 'Sistema'
  Write-Check 'Windows de 64 bits' $Estado.X64
  Write-Check 'Permisos de escritura' $Estado.Escritura $root
  $raiz = [IO.Path]::GetPathRoot($root)
  $drive = Get-PSDrive -Name $raiz.Substring(0, 1) -ErrorAction SilentlyContinue
  if ($drive) { Write-Host ("  [INFO] Espacio libre en {0}: {1:N1} GB" -f $raiz, ($drive.Free / 1GB)) -ForegroundColor DarkGray }
  $internet = Test-Internet
  if ($internet) { Write-Check 'Conexión a internet' $true 'Necesaria solo para descargas o npm.' }
  else { Write-Host '  [AVISO] Sin conexión a internet. Se puede abrir si todo está descargado.' -ForegroundColor Yellow }

  Write-Seccion 'Herramientas'
  if ($Estado.Tools.Java) { Write-Check 'Java 17+ (ejecutar)' $true ("{0} (versión {1})" -f $Estado.Tools.Java, $Estado.Tools.JavaVersion) }
  else { Write-Check 'Java 17+ (ejecutar)' $false 'No compatible o no instalado.' 'Se descargará al abrir.' }
  if ($Estado.Tools.Jdk) { Write-Check 'JDK 17+ (compilar)' $true ("{0} (versión {1})" -f $Estado.Tools.Jdk, $Estado.Tools.JdkVersion) }
  else { Write-Check 'JDK 17+ (compilar)' $false 'No disponible.' 'Se descargará al compilar.' }
  if ($Estado.Tools.Node) { Write-Check 'Node.js (compilar)' $true ("{0} (v{1})" -f $Estado.Tools.Node, $Estado.Tools.NodeVersion) }
  else { Write-Check 'Node.js (compilar)' $false 'No disponible.' 'Se descargará al compilar.' }
  if ($Estado.Tools.Npm) { Write-Check 'npm' $true $Estado.Tools.Npm } else { Write-Check 'npm' $false 'No disponible.' }
  Write-Check 'Maven wrapper' $Estado.Tools.MavenWrapper 'java_backend\mvnw.cmd'
  Write-Check 'Dependencias frontend' $Estado.Tools.NodeModules 'angular_frontend\node_modules'

  Write-Seccion 'Visual C++'
  if ($Estado.VC.Ok) {
    Write-Check 'Runtime x64' $true $Estado.VC.Detalle
  } else {
    Write-Check 'Runtime x64' $false $Estado.VC.Detalle 'Se ofrecerá instalar al abrir o compilar.'
    Write-Host ("  Versión requerida: {0}, {1}." -f $script:VCRedistNombre, $script:VCRedistVersion) -ForegroundColor Gray
    Write-Host ("  Enlace oficial: {0}" -f $script:VCRedistUrl) -ForegroundColor Cyan
    Write-Host ("  Carpeta de descarga: {0}" -f $script:VCRedistDir) -ForegroundColor DarkGray
  }

  Write-Seccion 'Compilación'
  $textoJar = if ($Estado.Jar.Jar) { $Estado.Jar.Jar } else { 'no existe' }
  Write-Host ("  Jar: {0}" -f $textoJar) -ForegroundColor DarkGray
  if ($Estado.Jar.JarFecha) { Write-Host ("  Fecha del jar: {0:dd/MM/yyyy HH:mm}" -f $Estado.Jar.JarFecha) -ForegroundColor DarkGray }
  if ($Estado.Jar.FuenteFecha) { Write-Host ("  Último cambio de código: {0:dd/MM/yyyy HH:mm}" -f $Estado.Jar.FuenteFecha) -ForegroundColor DarkGray }
  Write-Check 'Estado del jar' ($Estado.Jar.Estado -eq 'ok') $Estado.Jar.Estado
  Write-Check 'Íconos de la app' $Estado.Iconos.Ok $Estado.Iconos.Detalle

  Write-Seccion 'Base de datos'
  Write-Check 'Configuración' $Estado.Config.Ok $Estado.Config.Detalle
  if ($Estado.Config.Existe) { Write-Host '  [INFO] Archivo: java_backend\config-db.yaml' -ForegroundColor DarkGray }
  elseif ($Estado.Config.Ok) { Write-Host '  [INFO] No existe: se creará SQLite automáticamente.' -ForegroundColor DarkGray }
  else { Write-Host '  [INFO] No existe y falta el esquema SQLite del repositorio.' -ForegroundColor Yellow }
  if ($Estado.Config.Motor -ne 'sqlite' -and $Estado.Config.Url -match '//([^/:]+)(?::(\d+))?/') {
    $hostDb = $Matches[1]; $puertoDb = if ($Matches[2]) { [int]$Matches[2] } else { 3306 }
    $tcp = Test-Tcp $hostDb $puertoDb 1200
    if ($tcp) { Write-Check ("Servidor {0}:{1}" -f $hostDb, $puertoDb) $true 'Escuchando.' }
    else { Write-Check ("Servidor {0}:{1}" -f $hostDb, $puertoDb) $false 'No responde.' 'Enciende MariaDB/MySQL o cambia a SQLite.' }
  }

  Write-Seccion 'Aplicación'
  if ($Estado.Activa) {
    Write-Check 'Aplicación abierta' $true ("http://localhost:{0}" -f $Estado.Puerto)
  } else {
    Write-Host '  [INFO] Aplicación cerrada.' -ForegroundColor DarkGray
  }
  if (Test-PuertoLibre $Estado.Puerto) {
    Write-Host ('  [INFO] Puerto {0} libre.' -f $Estado.Puerto) -ForegroundColor DarkGray
  } elseif (-not $Estado.Activa) {
    $ocupado = Get-ProcesoPuerto $Estado.Puerto
    if ($ocupado) { Write-Host ("  [AVISO] El puerto {0} lo usa {1} (PID {2})." -f $Estado.Puerto, $ocupado.Nombre, $ocupado.Pid) -ForegroundColor Yellow }
    else { Write-Host ("  [AVISO] El puerto {0} está ocupado." -f $Estado.Puerto) -ForegroundColor Yellow }
  }
  $libre = Find-PuertoLibre 9000 9010
  if ($libre -gt 0) { Write-Host ("  [INFO] Primer puerto libre cercano: {0}" -f $libre) -ForegroundColor DarkGray }

  Write-Host ''
  if (-not $Estado.VC.Ok) { Write-Host '  Siguiente paso: elige [5] para preparar Visual C++.' -ForegroundColor Cyan }
  elseif ($Estado.Jar.Estado -ne 'ok') { Write-Host '  Siguiente paso: elige [2] para compilar y abrir.' -ForegroundColor Cyan }
  elseif (-not $Estado.Activa) { Write-Host '  Siguiente paso: elige [1] para abrir la aplicación.' -ForegroundColor Cyan }
  else { Write-Host '  Todo listo: la aplicación ya está abierta.' -ForegroundColor Green }
}

function Elegir-Modo {
  if ($Modo) { return $Modo }
  $ultimo = Get-ModoBuildGuardado 'prod'
  if ($script:Auto) { return $ultimo }
  return (Select-ModoBuild $ultimo 5)
}

function Asegurar-VCRedist {
  $vc = Get-VCRedistEstado
  if ($vc.Ok) { return $true }

  Write-Host ''
  Write-Host '  Falta Visual C++ para poder ejecutar Java.' -ForegroundColor Yellow
  Write-Host ("  Requerido: {0}, versión {1}." -f $script:VCRedistNombre, $script:VCRedistVersion) -ForegroundColor Gray
  Write-Host '  Sin él, Java puede fallar con: "VCRUNTIME140.dll no encontrado".' -ForegroundColor Gray
  Write-Host ("  Enlace oficial: {0}" -f $script:VCRedistUrl) -ForegroundColor Cyan
  Write-Host '  También puedes descargarlo y ejecutarlo manualmente por tu cuenta.' -ForegroundColor Gray

  if ($script:Auto -or -not $script:Interactivo) {
    Write-Host '  Instálalo manualmente y vuelve a ejecutar el asistente.' -ForegroundColor Yellow
    return $false
  }

  $exe = $script:VCRedistExe
  $reutilizar = $false
  if (Test-Path -LiteralPath $exe) {
    $item = Get-Item -LiteralPath $exe -ErrorAction SilentlyContinue
    if ($item -and $item.Length -gt 5MB -and (Test-FirmaMicrosoft $exe)) {
      $reutilizar = $true
      Write-Ok (" Instalador ya descargado: {0}" -f $exe)
    } else {
      Write-Aviso ' El instalador local no es válido; se descargará de nuevo.'
      Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
    }
  }

  if (-not $reutilizar) {
    if (-not (Preguntar-SiNo '¿Descargar ahora el instalador oficial?' $true)) {
      Write-Host '  Descarga cancelada. Puedes instalar Visual C++ manualmente con el enlace anterior.' -ForegroundColor Yellow
      return $false
    }
    if (-not (Test-Internet)) {
      Write-Host '  No hay conexión a internet para descargar el instalador.' -ForegroundColor Red
      Write-Host ("  Descárgalo manualmente desde: {0}" -f $script:VCRedistUrl) -ForegroundColor Yellow
      return $false
    }
    try {
      New-Item -ItemType Directory -Path $script:VCRedistDir -Force | Out-Null
    } catch {
      Write-Host ("  No se pudo crear la carpeta de descarga: {0}" -f $script:VCRedistDir) -ForegroundColor Red
      return $false
    }
    try {
      Invoke-Descarga $script:VCRedistUrl $exe $script:VCRedistNombre
    } catch {
      Write-Host ("  No se pudo descargar Visual C++: {0}" -f $_.Exception.Message) -ForegroundColor Red
      Write-Host ("  Descárgalo manualmente desde: {0}" -f $script:VCRedistUrl) -ForegroundColor Yellow
      return $false
    }
    if (-not (Test-FirmaMicrosoft $exe)) {
      Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
      Write-Host '  La firma digital del instalador no es válida. Descárgalo manualmente desde el enlace oficial.' -ForegroundColor Red
      return $false
    }
    Write-Ok (" Instalador guardado en: {0}" -f $exe)
  }

  Write-Host ("  Archivo: {0}" -f $exe) -ForegroundColor DarkGray
  if (-not (Preguntar-SiNo '¿Ejecutar el instalador ahora? (puede pedir permisos de administrador)' $true)) {
    Write-Host '  Puedes ejecutarlo manualmente con doble clic sobre el archivo indicado.' -ForegroundColor Yellow
    return $false
  }

  Write-Paso ' Abriendo el instalador de Visual C++...'
  $codigo = $null
  try {
    $proc = Start-Process -FilePath $exe -Verb RunAs -Wait -PassThru -ErrorAction Stop
    $codigo = $proc.ExitCode
  } catch {
    Write-Host ("  No se pudo ejecutar el instalador: {0}" -f $_.Exception.Message) -ForegroundColor Red
    Write-Host ("  Ejecútalo manualmente: {0}" -f $exe) -ForegroundColor Yellow
    return $false
  }

  Start-Sleep -Seconds 1
  $vc = Get-VCRedistEstado
  if ($vc.Ok) {
    Write-Ok (" Visual C++ listo: {0}" -f $vc.Detalle)
    if ($codigo -eq 3010) { Write-Aviso ' Windows pide reiniciar para completar la instalación.' }
    return $true
  }

  switch ($codigo) {
    3010 { Write-Aviso ' El instalador pide reiniciar Windows para completar la instalación.' }
    1638 { Write-Aviso ' Ya había otra versión instalada, pero sigue faltando vcruntime140.dll. Reinicia y vuelve a intentarlo.' }
    1618 { Write-Aviso ' Hay otra instalación en curso. Espera un momento y vuelve a intentarlo.' }
    default { Write-Host ("  El instalador terminó con código {0}, pero Visual C++ sigue sin detectarse." -f $codigo) -ForegroundColor Red }
  }
  Write-Host ("  También puedes ejecutarlo manualmente: {0}" -f $exe) -ForegroundColor Yellow
  return $false
}

function Asegurar-RequisitosCompilacion {
  if (-not (Test-EsWindowsX64)) { throw 'Se necesita Windows de 64 bits para usar las herramientas portátiles.' }
  if (-not (Asegurar-VCRedist)) { return $false }
  if (-not (Test-PermisosEscritura $root)) { throw 'No se puede escribir en la carpeta del proyecto.' }
  if (-not (Test-EspacioLibre $root 1.5)) { throw 'Se necesitan al menos 1,5 GB libres para compilar.' }
  $tools = Get-HerramientasEstado
  if (-not $tools.MavenWrapper -and -not (Get-Command 'mvn' -ErrorAction SilentlyContinue)) {
    throw 'Falta java_backend\mvnw.cmd y no hay Maven en PATH. Descarga el repositorio completo.'
  }
  $faltan = @()
  if (-not $tools.Jdk) { $faltan += 'JDK 17+' }
  if (-not $tools.Node) { $faltan += 'Node.js' }
  if (-not $tools.NodeModules) { $faltan += 'dependencias del frontend' }
  if ($faltan.Count -eq 0) { return $true }

  $internet = Test-Internet
  if (-not $internet) {
    throw ("Faltan componentes ({0}) y no hay conexión a internet para descargarlos." -f ($faltan -join ', '))
  }
  Write-Aviso (" Se necesitan descargar/instalar: {0}." -f ($faltan -join ', '))
  Write-Host '  Solo ocurre la primera vez; el resto queda guardado en el proyecto.' -ForegroundColor Gray
  if (-not $script:Auto -and $script:Interactivo) {
    $continuar = Preguntar-SiNo '¿Continuar ahora?' $true
    if (-not $continuar) { Write-Host '  Compilación cancelada. No se modificó nada.' -ForegroundColor Yellow }
    return $continuar
  }
  return $true
}

function Invocar-Build([string]$ModoBuild) {
  Write-Seccion 'Compilando'
  Write-Host '  Se compilará el frontend y se empaquetará la aplicación.' -ForegroundColor Gray
  Write-Host '  Puede tardar varios minutos la primera vez.' -ForegroundColor Gray
  $build = Join-Path $root 'Build-CanalVideos.ps1'
  if (-not (Test-Path -LiteralPath $build)) { throw 'No se encontró Build-CanalVideos.ps1.' }
  $psExe = Join-Path $PSHOME 'powershell.exe'
  if (-not (Test-Path -LiteralPath $psExe)) { $psExe = 'powershell.exe' }
  $argumentos = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $build, '-Modo', $ModoBuild, '-SinMenu')
  & $psExe @argumentos
  if ($LASTEXITCODE -ne 0) { throw ("La compilación falló con código {0}." -f $LASTEXITCODE) }
  $jar = Get-JarEstado
  if ($jar.Estado -ne 'ok') { throw 'La compilación terminó, pero el jar no es válido.' }
  Write-Ok ' Compilación lista.'
  return $true
}

function Asegurar-Compilacion([string]$ModoBuild, [switch]$Preguntar) {
  $jar = Get-JarEstado
  if ($jar.Estado -eq 'ok') { return $true }

  if ($jar.Estado -eq 'missing' -or $jar.Estado -eq 'invalid') {
    Write-Host ''
    Write-Host '  No hay una aplicación compilada válida.' -ForegroundColor Yellow
    if ($Preguntar -and -not $script:Auto -and $script:Interactivo) {
      if (-not (Preguntar-SiNo '¿Compilar ahora?' $true)) { return $false }
    }
    if (-not (Asegurar-RequisitosCompilacion)) { return $false }
    [void](Invocar-Build $ModoBuild)
    return $true
  }

  if ($jar.Estado -eq 'stale') {
    Write-Host ''
    Write-Host '  Hay cambios en el código que no están en la aplicación compilada.' -ForegroundColor Yellow
    Write-Host ("  Jar: {0:dd/MM/yyyy HH:mm}  |  Código: {1:dd/MM/yyyy HH:mm}" -f $jar.JarFecha, $jar.FuenteFecha) -ForegroundColor Gray
    if ($script:Auto) {
      if (-not (Asegurar-RequisitosCompilacion)) { return $false }
      [void](Invocar-Build $ModoBuild)
      return $true
    }
    if ($Preguntar -and $script:Interactivo) {
      if (Preguntar-SiNo '¿Recompilar ahora?' $true) {
        if (-not (Asegurar-RequisitosCompilacion)) { return $false }
        [void](Invocar-Build $ModoBuild)
      } else {
        Write-Aviso ' Se abrirá la versión compilada anterior.'
      }
    }
    return $true
  }

  return $true
}

function Asegurar-RequisitosEjecucion {
  if (-not (Asegurar-VCRedist)) { return $false }
  $cfg = Get-ConfigDbResumen
  if (-not $cfg.Ok) {
    Write-Host ''
    Write-Host '  La configuración de la base de datos necesita atención.' -ForegroundColor Yellow
    Write-Host ("  {0}" -f $cfg.Detalle) -ForegroundColor Gray
    if (-not $cfg.Existe -and $cfg.Detalle -match 'schema-sqlite') {
      Write-Host '  Falta database\schema-sqlite.sql. Descarga el repositorio completo.' -ForegroundColor Red
      return $false
    }
    if (-not (Test-PermisosEscritura (Join-Path $root 'java_backend'))) {
      Write-Host '  No se puede escribir en java_backend\; revisa los permisos de la carpeta.' -ForegroundColor Red
      return $false
    }
    if ($script:Auto -or -not $script:Interactivo) {
      Write-Host '  Corrige java_backend\config-db.yaml y vuelve a intentarlo.' -ForegroundColor Yellow
      return $false
    }
    if (-not (Preguntar-SiNo '¿Guardar una copia y volver a crear la configuración SQLite?' $false)) {
      Write-Host '  Corrige java_backend\config-db.yaml y vuelve a intentarlo.' -ForegroundColor Yellow
      return $false
    }
    $archivo = Join-Path $root 'java_backend\config-db.yaml'
    if (Test-Path -LiteralPath $archivo) {
      $bak = "$archivo.bak-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
      Move-Item -LiteralPath $archivo -Destination $bak -Force
      Write-Aviso (" Copia guardada: {0}" -f $bak)
    }
    [void](Ensure-ConfigDb)
    $cfg = Get-ConfigDbResumen
    if (-not $cfg.Ok) {
      Write-Host ("  No se pudo preparar la base de datos: {0}" -f $cfg.Detalle) -ForegroundColor Red
      return $false
    }
    Write-Ok (" Base de datos lista: {0}" -f $cfg.Detalle)
  }

  $tools = Get-HerramientasEstado
  if (-not $tools.Java) {
    Write-Host '  Java 17+ no está disponible; se preparará ahora.' -ForegroundColor Yellow
    if (-not (Test-Internet)) {
      Write-Host '  No hay conexión a internet para descargar Java.' -ForegroundColor Red
      return $false
    }
    try { [void](Resolve-JavaRuntime) } catch {
      Write-Host ("  No se pudo preparar Java: {0}" -f $_.Exception.Message) -ForegroundColor Red
      return $false
    }
  }
  if (-not (Test-PermisosEscritura (Join-Path $root 'java_backend'))) {
    Write-Host '  No se puede escribir en java_backend\ (configuración y clave).' -ForegroundColor Red
    return $false
  }
  return $true
}

function Invocar-Abrir([string]$ModoBuild, [switch]$Preguntar, [switch]$ForzarBuild) {
  $iconos = Ensure-IconosCanalVideos
  if (-not $iconos.Ok) {
    Write-Aviso (" No se pudieron preparar los íconos: {0}" -f $iconos.Detalle)
  } elseif ($iconos.Regenerado) {
    Write-Aviso ' Íconos regenerados; se comprobará si hace falta recompilar.'
  } elseif ($iconos.Restaurados.Count -gt 0) {
    Write-Aviso (" Íconos restaurados: {0}." -f ($iconos.Restaurados -join ', '))
  }

  if (-not $ModoBuild) { $ModoBuild = Elegir-Modo }
  if ($ForzarBuild) {
    if (-not (Asegurar-RequisitosCompilacion)) { return $false }
    [void](Invocar-Build $ModoBuild)
  } elseif (-not (Asegurar-Compilacion $ModoBuild -Preguntar:$Preguntar)) {
    return $false
  }
  if (-not (Asegurar-RequisitosEjecucion)) { return $false }

  $puertoActivo = Get-PuertoActivo
  if (Test-AppResponde -Puerto $puertoActivo -TimeOutSeg 2) {
    Write-Host ''
    Write-Ok (" CanalVideos ya está abierta en http://localhost:{0}" -f $puertoActivo)
    try { [void](Start-AppDetached -Puerto $puertoActivo -JarPath (Get-JarPath)) } catch {}
    return $true
  }

  if (-not (Test-PuertoLibre $script:Puerto)) {
    $ocupado = Get-ProcesoPuerto $script:Puerto
    $libre = Find-PuertoLibre 9000 9010
    if ($libre -le 0) {
      Write-Host ("  El puerto {0} está ocupado y no hay otro libre cerca." -f $script:Puerto) -ForegroundColor Red
      return $false
    }
    $nombre = if ($ocupado) { "{0} (PID {1})" -f $ocupado.Nombre, $ocupado.Pid } else { 'otro proceso' }
    Write-Aviso (" El puerto {0} está ocupado por {1}; se usará {2}." -f $script:Puerto, $nombre, $libre)
    $script:Puerto = $libre
  }

  Write-Seccion 'Abriendo la aplicación'
  Write-Host ("  Iniciando backend en http://localhost:{0} ..." -f $script:Puerto) -ForegroundColor Gray
  $launcherProc = $null
  $jarPath = Get-JarPath
  try {
    $launcherProc = Start-AppDetached -Puerto $script:Puerto -JarPath $jarPath
  } catch {
    Write-Host ("  No se pudo lanzar la aplicación: {0}" -f $_.Exception.Message) -ForegroundColor Red
    return $false
  }

  if (-not (Wait-AppReady -Puerto $script:Puerto -Segundos 90 -Launcher $launcherProc)) {
    $logDir = Join-Path $root 'logs'
    Show-BackendFailure (Join-Path $logDir ("api-{0}-error.log" -f $script:Puerto)) (Join-Path $logDir ("api-{0}.log" -f $script:Puerto))
    return $false
  }

  Write-Host ''
  Write-Ok (" Listo: CanalVideos está abierta en http://localhost:{0}" -f $script:Puerto)
  Write-Host '  La ventana de la app se abrirá sola. Puedes cerrar esta ventana o seguir en el menú.' -ForegroundColor Gray
  return $true
}

function Mostrar-Menu([object]$Estado) {
  Write-Seccion '¿Qué quieres hacer?'
  if ($Estado.Jar.Estado -ne 'ok') {
    Write-Host '  [1] Abrir CanalVideos' -ForegroundColor White
    Write-Host '      (recomendado: compilará lo que falte antes de abrir)' -ForegroundColor DarkGray
  } else {
    Write-Host '  [1] Abrir CanalVideos' -ForegroundColor White
  }
  Write-Host '  [2] Compilar y abrir' -ForegroundColor White
  Write-Host '  [3] Solo compilar' -ForegroundColor White
  Write-Host '  [4] Diagnóstico del sistema' -ForegroundColor White
  if (-not $Estado.VC.Ok) { Write-Host '  [5] Descargar/instalar Visual C++' -ForegroundColor White }
  Write-Host '  [0] Salir' -ForegroundColor DarkGray
}

function Ejecutar-Menu {
  while ($true) {
    $estado = Obtener-Estado
    Mostrar-Resumen $estado
    Mostrar-Menu $estado
    $defecto = '1'
    $opcion = Read-Host ("  Elige una opción y pulsa Enter [{0}]" -f $defecto)
    if (-not $opcion) { $opcion = $defecto }
    try {
      switch ($opcion.Trim()) {
        '1' {
          $modoBuild = if ($estado.Jar.Estado -eq 'ok') { $null } else { Elegir-Modo }
          [void](Invocar-Abrir $modoBuild -Preguntar)
          Pausar
        }
        '2' {
          $modoBuild = Elegir-Modo
          [void](Invocar-Abrir $modoBuild -ForzarBuild)
          Pausar
        }
        '3' {
          $modoBuild = Elegir-Modo
          if (Asegurar-RequisitosCompilacion) { [void](Invocar-Build $modoBuild) }
          Pausar
        }
        '4' {
          Mostrar-Diagnostico (Obtener-Estado)
          Pausar
        }
        '5' {
          [void](Asegurar-VCRedist)
          Pausar
        }
        '0' { return }
        default {
          Write-Host '  Opción no válida. Usa 1, 2, 3, 4, 5 o 0.' -ForegroundColor Yellow
          Start-Sleep -Milliseconds 900
        }
      }
    } catch {
      Mostrar-Error 'Acción del asistente' $_
      Pausar
    }
  }
}

try {
  if ($Diagnostico) {
    Mostrar-Resumen (Obtener-Estado)
    Mostrar-Diagnostico (Obtener-Estado)
    Pausar
    exit 0
  }

  if ($script:Auto) {
    $modoBuild = Elegir-Modo
    $ok = Invocar-Abrir $modoBuild -Preguntar
    if ($ok) { exit 0 } else { exit 1 }
  }

  if (-not $script:Interactivo) {
    Mostrar-Resumen (Obtener-Estado)
    Write-Host ''
    Write-Host '  Modo no interactivo: usa -Auto para compilar/abrir sin menú o -Diagnostico para revisar.' -ForegroundColor Yellow
    exit 0
  }

  Ejecutar-Menu
  exit 0
} catch {
  Mostrar-Error 'Asistente de compilación y arranque' $_
  if ($script:Interactivo -and -not $script:Auto) { Pausar }
  exit 1
}
