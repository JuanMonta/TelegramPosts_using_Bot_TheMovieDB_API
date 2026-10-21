<#
.SYNOPSIS
  Launch-CanalVideos.ps1 -- modo app (--app) con splash, tray, single-instance y Job Object.
  Copia fiel del patrón Launch-BinanceRendimiento.ps1 (single jar en un puerto).
#>
param([int]$Port = $(if ($env:PORT) { [int]$env:PORT } else { 9000 }), [string]$JarPath = $null,
  # Pruebas: permite varias instancias a la vez (puertos auto-incrementales).
  # Sin esto, un segundo lanzamiento trae al frente la app ya abierta y sale.
  [switch]$Multi)

$AppName   = "CanalVideos"
$BrandName = "CanalStudio"
$AppTitle  = "CanalStudio"
$JarName   = "crow-0.0.1-SNAPSHOT.jar"
$SplashTit = "CanalStudio"
$SplashSub = "Organiza y publica tu contenido"

# Detecta GUI real (no solo UserInteractive) con timeout para no colgarse en headless/test
$guiAvailable = $false
try {
  if ([Environment]::UserInteractive) {
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop | Out-Null
    Add-Type -AssemblyName System.Drawing -ErrorAction Stop | Out-Null
    $testJob = Start-Job -ScriptBlock { Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.Form; $f.Dispose(); return $true } -ErrorAction SilentlyContinue
    if ($testJob -and (Wait-Job $testJob -Timeout 3)) { $guiAvailable = (Receive-Job $testJob -ErrorAction SilentlyContinue) -eq $true }
    if ($testJob) { Remove-Job $testJob -Force -ErrorAction SilentlyContinue }
  }
} catch { $guiAvailable = $false }
if (-not $guiAvailable) { $env:CV_SIN_UI = '1'; Write-Host "[INFO] Modo headless (sin splash/tray) - test o sesion sin escritorio" -ForegroundColor DarkGray }
if ($guiAvailable) {
  try {
    if (-not ([System.Management.Automation.PSTypeName]'Win32').Type) {
      Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32 { [DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow(); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow); }
"@ -ErrorAction SilentlyContinue
    }
    $hwnd = [Win32]::GetConsoleWindow()
    if ($hwnd -ne [IntPtr]::Zero) { [void][Win32]::ShowWindow($hwnd, 0) }
    try {
      $self = [System.Diagnostics.Process]::GetCurrentProcess()
      if ($self.MainWindowHandle -ne [IntPtr]::Zero) { [void][Win32]::ShowWindow($self.MainWindowHandle, 0) }
      $ppid = (Get-CimInstance Win32_Process -Filter "ProcessId=$($self.Id)" -ErrorAction SilentlyContinue).ParentProcessId
      if ($ppid) {
        $parent = Get-Process -Id $ppid -ErrorAction SilentlyContinue
        if ($parent -and $parent.MainWindowHandle -ne [IntPtr]::Zero) { [void][Win32]::ShowWindow($parent.MainWindowHandle, 0) }
      }
    } catch {}
  } catch {}
}

$ErrorActionPreference='Stop'
$root=$PSScriptRoot
Set-Location -LiteralPath $root
. (Join-Path $root 'CanalVideos.Tools.ps1')

$iconos = Ensure-IconosCanalVideos
if (-not $iconos.Ok) {
  Write-Host ("[AVISO] No se pudieron preparar los íconos: {0}" -f $iconos.Detalle) -ForegroundColor Yellow
} elseif ($iconos.Regenerado) {
  Write-Host '[INFO] Íconos regenerados; se aplicarán al recompilar el frontend.' -ForegroundColor DarkGray
}

if (-not $JarPath) { $JarPath = Get-JarPath }
if(-not $JarPath -or -not (Test-Path -LiteralPath $JarPath)){
  [void](Show-Mensaje "No se encontró la aplicación compilada.`n`nEjecuta AsistenteDeCompilacion-CanalVideos.bat (te guía) o Build-And-Launch.bat." $BrandName)
  Write-Host '[ERROR] Falta el jar: ejecuta AsistenteDeCompilacion-CanalVideos.bat o Build-And-Launch.bat.' -ForegroundColor Red
  exit 1
}
if (-not (Test-JarValido $JarPath)) {
  [void](Show-Mensaje "La aplicación compilada está incompleta o dañada.`n`nVuelve a compilar con Build-And-Launch.bat o AsistenteDeCompilacion-CanalVideos.bat." $BrandName)
  Write-Host '[ERROR] Jar inválido o incompleto. Vuelve a compilar.' -ForegroundColor Red
  exit 1
}
$vc = Get-VCRedistEstado
if (-not $vc.Ok) {
  $texto = "Falta Visual C++ 2015-2022 x64 (14.x).`n`n$($vc.Detalle)`n`nJava puede fallar con `"VCRUNTIME140.dll no encontrado`".`n`nEnlace oficial: $script:VCRedistUrl`n`nEjecuta AsistenteDeCompilacion-CanalVideos.bat y elige [5] para descargarlo e instalarlo."
  [void](Show-Mensaje $texto $BrandName)
  Write-Host '[ERROR] Falta Visual C++ 2015-2022 x64. Ejecuta AsistenteDeCompilacion-CanalVideos.bat y elige [5].' -ForegroundColor Red
  Write-Host ("        Enlace oficial: {0}" -f $script:VCRedistUrl) -ForegroundColor Yellow
  exit 1
}

try { $javaExe = Resolve-JavaRuntime } catch {
  [void](Show-Mensaje "No se pudo preparar Java: $($_.Exception.Message)" $BrandName)
  Write-Host ("[ERROR] No se pudo preparar Java: {0}" -f $_.Exception.Message) -ForegroundColor Red
  exit 1
}

# --- Single instance: Mutex + traer al frente ---
# Desde el milisegundo cero: si el Mutex ya existe, hay app abierta.
$mutexName = "Local\CanalVideos_SingleInstance"
# En modo pruebas cada lanzamiento usa un Mutex propio: siempre es "el primero".
if ($Multi) { $mutexName = "${mutexName}_Multi_$PID" }
$instanceDir = Join-Path $env:LOCALAPPDATA $AppName
$instanceFile = Join-Path $instanceDir "instance.json"
try{ New-Item -ItemType Directory -Path $instanceDir -Force | Out-Null } catch{}
$createdNew = $false
$mutex = $null
try { $mutex = New-Object System.Threading.Mutex($false, $mutexName, [ref]$createdNew) } catch { $createdNew = $true }
if (-not $createdNew) {
  # Ya hay instancia: trae al frente y sal
  $existingPort = $Port
  $existingProfile = Join-Path $root ".chrome-profile"
  try {
    if (Test-Path $instanceFile) {
      $inst = Get-Content $instanceFile -Raw | ConvertFrom-Json -ErrorAction SilentlyContinue
      if ($inst -and $inst.port) { $existingPort = $inst.port; if($inst.profile){ $existingProfile = $inst.profile } }
    }
  } catch {}
  try {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Front {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
"@ -ErrorAction SilentlyContinue
  } catch {}
  $found = $false
  foreach($procName in @("chrome","msedge")){
    $procs = Get-Process -Name $procName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*CanalStudio*" -or $_.MainWindowTitle -like "*CanalVideos*" -or $_.MainWindowTitle -like "*CANAL DE VIDEOS*" }
    foreach($p in $procs){
      if($p.MainWindowHandle -ne [IntPtr]::Zero){
        try{
          if([Win32Front]::IsIconic($p.MainWindowHandle)){ [void][Win32Front]::ShowWindow($p.MainWindowHandle, 9) }
          [void][Win32Front]::SetForegroundWindow($p.MainWindowHandle)
          $found = $true; break
        } catch{}
      }
    }
    if($found){ break }
  }
  if(-not $found){
    try{ Start-Process "http://localhost:$existingPort" | Out-Null } catch{}
  }
  # Aviso no bloqueante (balloon) en lugar de MessageBox que colgaba el test
  try{
    $tmpIcon = New-Object System.Windows.Forms.NotifyIcon
    $tmpIcon.Icon = [System.Drawing.SystemIcons]::Information
    $tmpIcon.Visible = $true
    $tmpIcon.ShowBalloonTip(3000, $BrandName, "Ya esta corriendo en http://localhost:$existingPort", [System.Windows.Forms.ToolTipIcon]::Info)
    Start-Sleep -Seconds 2
    $tmpIcon.Visible = $false; $tmpIcon.Dispose()
  } catch{}
  if($mutex){ try{ $mutex.Dispose() } catch{} }
  exit 0
}
# Somos la primera instancia: el mutex quedará tomado hasta el final

$script:exitRequested = $false

function Test-PortFree([int]$p){ try{ $l=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any,$p); $l.Start(); $l.Stop(); return $true } catch { return $false } }
$orig=$Port; while(-not (Test-PortFree $Port)){ $Port++; if($Port -gt $orig+30){
  [void](Show-Mensaje ("No hay puertos libres cerca de {0}.`n`nCierra otras aplicaciones y vuelve a intentarlo." -f $orig) $BrandName)
  if($mutex){ try{ $mutex.ReleaseMutex(); $mutex.Dispose() } catch{} }; exit 1
} }
# En modo pruebas el registro de instancia es por puerto (no pisa el de la app real).
if ($Multi) { $instanceFile = Join-Path $instanceDir "instance-$Port.json" }

# --- Validación de la configuración antes de arrancar Java ---
$cfg = Get-ConfigDbResumen
if (-not $cfg.Ok) {
  $texto = "La configuración de la base de datos no es válida.`n`n{0}`n`nRevisa java_backend\config-db.yaml desde Configuración -> Datos.`nSi quieres volver a SQLite, renombra ese archivo y vuelve a iniciar." -f $cfg.Detalle
  [void](Show-Mensaje $texto $BrandName)
  Write-Host ("[ERROR] Configuración de base de datos inválida: {0}" -f $cfg.Detalle) -ForegroundColor Red
  try{ Remove-Item $instanceFile -Force -ErrorAction SilentlyContinue } catch{}
  if($mutex){ try{ $mutex.ReleaseMutex(); $mutex.Dispose() } catch{} }
  exit 1
}
Write-Host ("[INFO] Base de datos: {0}" -f $cfg.Detalle) -ForegroundColor DarkGray
# Limpia huerfano previo si quedo instance.json con puerto que ahora esta libre pero el archivo sigue
# (no hace falta matar, solo sobreescribiremos)

# Guarda instancia para que la 2da la encuentre
$profilePath = Join-Path $root ".chrome-profile"
$instanceInfo = @{ port = $Port; pid = $PID; profile = $profilePath; started = (Get-Date).ToString("o") } | ConvertTo-Json
try{ Set-Content -Path $instanceFile -Value $instanceInfo -Force } catch{}

# IP LAN para el menu del tray
$lanIp = $null; $lanUrl = $null
try{ $lanIp=(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.*" -and $_.PrefixOrigin -ne "WellKnown"} | Select-Object -First 1 -ExpandProperty IPAddress); if($lanIp){ $lanUrl="http://$lanIp`:$Port" } } catch{}

# --- Splash (carga) - solo si hay GUI ---
$splash = $null; $lblStatus = $null
function Set-SplashStatus([string]$txt){ if($lblStatus){ try{ $lblStatus.Text = $txt } catch{} } }
if ($guiAvailable) {
try {
Add-Type -AssemblyName System.Windows.Forms, System.Drawing -ErrorAction Stop | Out-Null
$splash = New-Object System.Windows.Forms.Form
$splash.Text = $BrandName
$splash.Size = New-Object System.Drawing.Size(460,240)
$splash.StartPosition = "CenterScreen"
$splash.FormBorderStyle = "None"
$splash.BackColor = [System.Drawing.Color]::FromArgb(15,23,42)
$splash.ForeColor = [System.Drawing.Color]::FromArgb(245,245,245)
$splash.ShowInTaskbar = $false
$splash.TopMost = $false
$splash.Font = New-Object System.Drawing.Font("Segoe UI",9)

try {
  $ico = Join-Path $root "CanalVideos.ico"
  if (Test-Path $ico) { $splash.Icon = New-Object System.Drawing.Icon($ico) }
} catch {}

$lblTitle = New-Object System.Windows.Forms.Label
$lblTitle.Text = $SplashTit
$lblTitle.Font = New-Object System.Drawing.Font("Segoe UI",16,[System.Drawing.FontStyle]::Bold)
$lblTitle.ForeColor = [System.Drawing.Color]::FromArgb(46,166,255)
$lblTitle.AutoSize = $false; $lblTitle.Size = New-Object System.Drawing.Size(420,34); $lblTitle.Location = New-Object System.Drawing.Point(20,22)
$lblTitle.TextAlign = "MiddleCenter"
$splash.Controls.Add($lblTitle)

$lblSub = New-Object System.Windows.Forms.Label
$lblSub.Text = $SplashSub
$lblSub.Font = New-Object System.Drawing.Font("Segoe UI",9)
$lblSub.ForeColor = [System.Drawing.Color]::FromArgb(150,160,180)
$lblSub.AutoSize = $false; $lblSub.Size = New-Object System.Drawing.Size(420,22); $lblSub.Location = New-Object System.Drawing.Point(20,60)
$lblSub.TextAlign = "MiddleCenter"
$splash.Controls.Add($lblSub)

$lblStatus = New-Object System.Windows.Forms.Label
$lblStatus.Text = "Iniciando..."
$lblStatus.Font = New-Object System.Drawing.Font("Consolas",9)
$lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(120,135,160)
$lblStatus.AutoSize = $false; $lblStatus.Size = New-Object System.Drawing.Size(420,20); $lblStatus.Location = New-Object System.Drawing.Point(20,100)
$lblStatus.TextAlign = "MiddleCenter"
$splash.Controls.Add($lblStatus)

$lblProgress = New-Object System.Windows.Forms.ProgressBar
$lblProgress.Minimum = 0; $lblProgress.Maximum = 100; $lblProgress.Value = 0
$lblProgress.Size = New-Object System.Drawing.Size(420,8); $lblProgress.Location = New-Object System.Drawing.Point(20,132)
$lblProgress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
$splash.Controls.Add($lblProgress)

$splash.Show(); $splash.Refresh()
$lblProgress.Value = 8
} catch { }
}

# --- Logs ---
$logDir = Join-Path $root "logs"
try{ New-Item -ItemType Directory -Path $logDir -Force | Out-Null } catch{}
$logOut = Join-Path $logDir "api-$Port.log"
$logErr = Join-Path $logDir "api-$Port-error.log"

# --- NotifyIcon (tray) ---
$notifyIcon = $null
if ($guiAvailable) {
try{
$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$trayIco = $null
try {
  $ico = Join-Path $root "CanalVideos.ico"
  if (Test-Path $ico) { $trayIco = New-Object System.Drawing.Icon($ico,16,16) }
  else { $trayIco = [System.Drawing.SystemIcons]::Application }
} catch { $trayIco = [System.Drawing.SystemIcons]::Application }
$notifyIcon.Icon = $trayIco
$notifyIcon.Text = "$BrandName :$Port"
$notifyIcon.Visible = $true
$contextMenu = New-Object System.Windows.Forms.ContextMenuStrip
$menuOpen = $contextMenu.Items.Add("Abrir navegador")
$menuLanCopy = $null; $menuLanShow = $null
if($lanUrl){
  $menuLanCopy = $contextMenu.Items.Add("Copiar URL LAN")
  $menuLanShow = $contextMenu.Items.Add("Mostrar URL LAN")
  $contextMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null
}
$menuLogs = $contextMenu.Items.Add("Mostrar carpeta de logs")
$menuExit = $contextMenu.Items.Add("Cerrar todo")
$notifyIcon.ContextMenuStrip = $contextMenu
$menuExit.Add_Click({ $script:exitRequested = $true; $notifyIcon.Visible=$false })
$menuOpen.Add_Click({ Start-Process "http://localhost:$Port" })
if($menuLanCopy){ $menuLanCopy.Add_Click({ Set-Clipboard $lanUrl; $notifyIcon.ShowBalloonTip(2200,"LAN","Copiado: $lanUrl",[System.Windows.Forms.ToolTipIcon]::Info) }) }
if($menuLanShow){ $menuLanShow.Add_Click({ [System.Windows.Forms.MessageBox]::Show("Accede desde tu celular (misma WiFi):`n`n$lanUrl`n`nLocal: http://localhost:$Port",$BrandName,[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null }) }
$menuLogs.Add_Click({ $dir = Join-Path $root "logs"; if(Test-Path $dir){ Start-Process explorer.exe $dir } })
$notifyIcon.Add_DoubleClick({ Start-Process "http://localhost:$Port" })
$notifyIcon.BalloonTipTitle = $BrandName
$notifyIcon.BalloonTipText = if($lanUrl){"Listo en :$Port - LAN $lanUrl"} else {"Listo en :$Port"}
} catch { Write-Host "[AVISO] Tray no disponible, continuo sin tray" -ForegroundColor Yellow; $notifyIcon = $null }
}

# --- Job Object anti-huerfano: backend muere si el launcher muere ---
$jobHandle = [IntPtr]::Zero
try{
  if (-not ([System.Management.Automation.PSTypeName]'JobObject').Type) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class JobObject {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr CreateJobObject(IntPtr a, string n);
  [DllImport("kernel32.dll")] public static extern bool SetInformationJobObject(IntPtr h, int c, ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION i, int s);
  [DllImport("kernel32.dll")] public static extern bool AssignProcessToJobObject(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct JOBOBJECT_BASIC_LIMIT_INFORMATION { public Int64 PerProcessUserTimeLimit; public Int64 PerJobUserTimeLimit; public UInt32 LimitFlags; public UIntPtr MinimumWorkingSetSize; public UIntPtr MaximumWorkingSetSize; public UInt32 ActiveProcessLimit; public IntPtr Affinity; public UInt32 PriorityClass; public UInt32 SchedulingClass; }
  [StructLayout(LayoutKind.Sequential)] public struct IO_COUNTERS { public UInt64 ReadOperationCount; public UInt64 WriteOperationCount; public UInt64 OtherOperationCount; public UInt64 ReadTransferCount; public UInt64 WriteTransferCount; public UInt64 OtherTransferCount; }
  [StructLayout(LayoutKind.Sequential)] public struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION { public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation; public IO_COUNTERS IoInfo; public UIntPtr ProcessMemoryLimit; public UIntPtr JobMemoryLimit; public UIntPtr PeakProcessMemoryUsed; public UIntPtr PeakJobMemoryUsed; }
  public const int JobObjectExtendedLimitInformation = 9;
  public const int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
}
"@ -ErrorAction SilentlyContinue
  }
  $jobHandle = [JobObject]::CreateJobObject([IntPtr]::Zero, "CanalVideos_Job_$PID")
  $info = New-Object JobObject+JOBOBJECT_EXTENDED_LIMIT_INFORMATION
  $info.BasicLimitInformation.LimitFlags = [JobObject]::JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
  [void][JobObject]::SetInformationJobObject($jobHandle, [JobObject]::JobObjectExtendedLimitInformation, [ref]$info, [System.Runtime.InteropServices.Marshal]::SizeOf($info))
} catch{}

function Cleanup-AfterFailure {
  if($splash){ try{ $splash.Close(); $splash.Dispose() } catch{} }
  if($notifyIcon){ try{ $notifyIcon.Visible=$false; $notifyIcon.Dispose() } catch{} }
  if($jobHandle -ne [IntPtr]::Zero){ try{ [void][JobObject]::CloseHandle($jobHandle) } catch{} }
  try{ Remove-Item $instanceFile -Force -ErrorAction SilentlyContinue } catch{}
  if($mutex){ try{ $mutex.ReleaseMutex(); $mutex.Dispose() } catch{} }
}

Set-SplashStatus "Iniciando backend (puerto $Port)..."
if($lblProgress){ try{ $lblProgress.Value = 25 } catch{} }
# --- Clave maestra AES (canonica: java_backend\clave_maestra.bin) ---
# Sin esto el backend usa/crea otra clave segun su carpeta de arranque
# y no puede descifrar lo guardado ("No se pudo descifrar lo guardado").
try {
  [void](Ensure-ConfigDb)
  $claveBin = Ensure-ClaveMaestra
  $env:CROW_CLAVE_MAESTRA = [Convert]::ToBase64String([IO.File]::ReadAllBytes($claveBin))
} catch {
  $detalle = $_.Exception.Message
  [void](Show-Mensaje ("No se pudo preparar la configuración ni la clave maestra.`n`n{0}`n`nComprueba que la carpeta del proyecto permita escribir." -f $detalle) $BrandName)
  Write-Host ("[ERROR] Configuración/clave: {0}" -f $detalle) -ForegroundColor Red
  Cleanup-AfterFailure
  exit 1
}
try {
  $apiProc=Start-Process -FilePath $javaExe -ArgumentList @("-jar","`"$JarPath`"","--server.port=$Port") -WorkingDirectory $root -PassThru -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
} catch {
  $detalle = $_.Exception.Message
  [void](Show-Mensaje ("No se pudo iniciar Java.`n`n{0}`n`nComprueba que el puerto {1} esté libre y vuelve a intentarlo." -f $detalle, $Port) $BrandName)
  Write-Host ("[ERROR] Java no pudo arrancar: {0}" -f $detalle) -ForegroundColor Red
  Cleanup-AfterFailure
  exit 1
}
if(-not $apiProc){ Cleanup-AfterFailure; exit 1 }
if($jobHandle -ne [IntPtr]::Zero){ try{ [void][JobObject]::AssignProcessToJobObject($jobHandle, $apiProc.Handle) } catch{} }

$ready=$false; $deadline=(Get-Date).AddSeconds(75)
while((Get-Date) -lt $deadline){
  if($apiProc.HasExited){
    $sugerencia = Get-BackendSugerencia $logErr
    Cleanup-AfterFailure
    Show-BackendFailure $logErr $logOut
    if($guiAvailable){
      Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
      [System.Windows.Forms.MessageBox]::Show(("El backend se cerró (código {0}).`n`n{1}`n`nLog: {2}" -f $apiProc.ExitCode, $sugerencia, $logErr),$BrandName,[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
    exit 1
  }
  if($script:exitRequested){ break }
  if($guiAvailable){ try{ [System.Windows.Forms.Application]::DoEvents() } catch{} }
  Set-SplashStatus "Esperando backend http://localhost:$Port ..."
  if($lblProgress){ try{ $lblProgress.Value = 40 } catch{} }
  try{ $null=Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://localhost:$Port/api/titulos" -ErrorAction Stop; $ready=$true; break } catch { Start-Sleep -Milliseconds 700 }
}
if(-not $ready -and -not $script:exitRequested){
  Set-SplashStatus "El backend tardó demasiado..."
  if($lblProgress){ try{ $lblProgress.Value = 70 } catch{} }
  Start-Sleep -Milliseconds 600
  $sugerencia = Get-BackendSugerencia $logErr
  if(-not $apiProc.HasExited){ try{ Stop-Process -Id $apiProc.Id -Force -ErrorAction SilentlyContinue } catch{} }
  Cleanup-AfterFailure
  Show-BackendFailure $logErr $logOut
  if($guiAvailable){
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
    [System.Windows.Forms.MessageBox]::Show(("La aplicación no respondió a tiempo.`n`n{0}`n`nLog: {1}" -f $sugerencia, $logErr),$BrandName,[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
  }
  exit 1
}
if($script:exitRequested){ if(-not $apiProc.HasExited){ Stop-Process -Id $apiProc.Id -Force -ErrorAction SilentlyContinue }; Cleanup-AfterFailure; exit 0 }

Set-SplashStatus "Backend listo - abriendo app..."
if($lblProgress){ try{ $lblProgress.Value = 95 } catch{} }
Start-Sleep -Milliseconds 400
if($splash){ $splash.Close(); $splash.Dispose() }

$browser=$null; $cands=@("$env:ProgramFiles\Google\Chrome\Application\chrome.exe","${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe","$env:LocalAppData\Google\Chrome\Application\chrome.exe","$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe","${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"); foreach($c in $cands){ if(Test-Path -LiteralPath $c){ $browser=$c; break } }
if(-not $browser){ foreach($n in @('chrome','msedge')){ $cmd=Get-Command $n -ErrorAction SilentlyContinue; if($cmd){ $browser=$cmd.Source; break } } }
if(-not $browser){
  Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
  [System.Windows.Forms.MessageBox]::Show("Chrome/Edge no encontrado.`nAbre a mano http://localhost:$Port`nBackend sigue en tray.","$BrandName") | Out-Null
  $browserProc=$null
} else {
  $profile=$profilePath
  $browserProc=Start-Process -FilePath $browser -ArgumentList @("--app=http://localhost:$Port","--user-data-dir=`"$profile`"") -PassThru
  if($notifyIcon){ $notifyIcon.ShowBalloonTip(3000) }
  # --- Ventana propia: aislar AppUserModelID para no agrupar con otras apps --app (ej. BinanceRendimiento) ---
  # Windows agrupa en la barra por AppUserModelID; Edge --app sin ID explícito comparte el default
  # y por eso ambas apps caen bajo el mismo botón "A". Aquí le asignamos un ID propio a la ventana.
  try {
    if (-not ([System.Management.Automation.PSTypeName]'WinAppId').Type) {
      Add-Type @"
using System;
using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential)] public struct PropertyKey { public Guid fmtid; public int pid; }
[StructLayout(LayoutKind.Explicit)] public struct PropVariant { [FieldOffset(0)] public ushort vt; [FieldOffset(2)] public ushort wReserved1; [FieldOffset(4)] public ushort wReserved2; [FieldOffset(6)] public ushort wReserved3; [FieldOffset(8)] public IntPtr ptr; }
[ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IPropertyStore { int GetCount(out uint c); int GetAt(uint i, out PropertyKey k); int GetValue(ref PropertyKey k, out PropVariant v); int SetValue(ref PropertyKey k, ref PropVariant v); int Commit(); }
public class WinAppId {
  [DllImport("shell32.dll")] public static extern int SHGetPropertyStoreForWindow(IntPtr hwnd, ref Guid iid, out IPropertyStore store);
  [DllImport("ole32.dll")] public static extern int PropVariantClear(ref PropVariant pv);
  public static void SetWindowAppId(IntPtr hwnd, string appId) {
    Guid iid = new Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99");
    IPropertyStore store;
    int hr = SHGetPropertyStoreForWindow(hwnd, ref iid, out store);
    if (hr != 0 || store == null) return;
    PropertyKey key = new PropertyKey { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };
    PropVariant pv = new PropVariant(); pv.vt = 31; pv.ptr = Marshal.StringToCoTaskMemUni(appId);
    try { store.SetValue(ref key, ref pv); store.Commit(); } finally { PropVariantClear(ref pv); Marshal.ReleaseComObject(store); }
  }
}
"@ -ErrorAction SilentlyContinue
    }
    $appId = "Crow.CanalVideos"
    $deadlineAppId = (Get-Date).AddSeconds(14)
    while ((Get-Date) -lt $deadlineAppId) {
      $cands = @()
      foreach ($pn in @("msedge","chrome")) {
        $ps = Get-Process -Name $pn -ErrorAction SilentlyContinue
        foreach ($p in $ps) {
          try {
            $ci = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue
            if ($ci -and $ci.CommandLine -like "*$profile*") { $cands += $p }
          } catch {}
        }
      }
      $set = 0
      foreach ($p in $cands) {
        try {
          if ($p.MainWindowHandle -ne [IntPtr]::Zero) { [WinAppId]::SetWindowAppId($p.MainWindowHandle, $appId); $set++ }
          # también ventanas hijas con título de la app (Edge reutiliza proceso padre)
          foreach ($hw in @($p.MainWindowHandle)) {}
        } catch {}
      }
      if ($set -gt 0) { break }
      Start-Sleep -Milliseconds 650
    }
  } catch {}
}

# Espera cruzada
try {
  while((-not $script:exitRequested) -and (-not $apiProc.HasExited) -and (-not ($browserProc -and $browserProc.HasExited))){
    if($guiAvailable){ try{ [System.Windows.Forms.Application]::DoEvents() } catch{} }
    Start-Sleep -Milliseconds 500
    if($browserProc -and $browserProc.HasExited){
      $alive = @()
      foreach ($pn in @("msedge","chrome")) {
        foreach ($pp in (Get-Process -Name $pn -ErrorAction SilentlyContinue)) {
          $ci2 = Get-CimInstance Win32_Process -Filter "ProcessId=$($pp.Id)" -ErrorAction SilentlyContinue
          if ($ci2 -and $ci2.CommandLine -like "*$profile*") { $alive += $pp }
        }
      }
      if(-not $alive){ break }
    }
  }
} finally {
  if($notifyIcon){ $notifyIcon.Visible=$false; $notifyIcon.Dispose() }
  if($jobHandle -ne [IntPtr]::Zero){ try{ [void][JobObject]::CloseHandle($jobHandle) } catch{} }
  try{ Remove-Item $instanceFile -Force -ErrorAction SilentlyContinue } catch{}
  if($mutex){ try{ $mutex.ReleaseMutex(); $mutex.Dispose() } catch{} }
  if(-not $apiProc.HasExited){
    try{ Stop-Process -Id $apiProc.Id -Force -ErrorAction SilentlyContinue } catch{}
    for($i=0;$i -lt 5;$i++){ if($apiProc.HasExited){ break }; Start-Sleep -Seconds 1 }
  }
  if($browserProc -and -not $browserProc.HasExited){
    try{ Stop-Process -Id $browserProc.Id -Force -ErrorAction SilentlyContinue } catch{}
  }
}