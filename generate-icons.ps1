# generate-icons.ps1 — CanalVideos
# Genera el ícono de la app (Telegram + Video) en múltiples tamaños y arma favicon.ico
# Diseño: cuadrado redondeado azul Telegram con play central blanco y perforaciones de film
param()
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
try { $global:OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
Add-Type -AssemblyName System.Drawing

$root = $PSScriptRoot
$outDir = Join-Path $root 'angular_frontend\src'
$assetsIconsDir = Join-Path $root 'angular_frontend\src\assets\icons'
New-Item -ItemType Directory -Path $assetsIconsDir -Force | Out-Null

function New-RoundedRectPath($x,$y,$w,$h,$r){
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r*2
  $path.AddArc($x, $y, $d, $d, 180, 90)
  $path.AddArc($x+$w-$d, $y, $d, $d, 270, 90)
  $path.AddArc($x+$w-$d, $y+$h-$d, $d, $d, 0, 90)
  $path.AddArc($x, $y+$h-$d, $d, $d, 90, 90)
  $path.CloseFigure()
  return $path
}

function Generate-Icon($size, $outPath){
  $bmp = New-Object System.Drawing.Bitmap $size,$size
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
  $g.Clear([System.Drawing.Color]::Transparent)

  # Fondo redondeado con gradiente
  $r = [int]($size * 0.22)
  $bgPath = New-RoundedRectPath 0 0 $size $size $r
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point 0,0),
    (New-Object System.Drawing.Point $size,$size),
    [System.Drawing.Color]::FromArgb(15,23,42),   # #0F172A
    [System.Drawing.Color]::FromArgb(14,165,233)  # #0EA5E9 / Telegram blue
  )
  # Ajuste intermedio para simular 3 paradas: overlay con segundo gradiente
  $g.FillPath($brush, $bgPath)
  $brush.Dispose()

  # Brillo superior sutil
  if($size -ge 48){
    $hiPath = New-RoundedRectPath 1 1 ($size-2) ([int]($size*0.48)) $r
    $hiBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
      (New-Object System.Drawing.Point 0,0),
      (New-Object System.Drawing.Point 0,([int]($size*0.5))),
      [System.Drawing.Color]::FromArgb(45,255,255,255),
      [System.Drawing.Color]::FromArgb(0,255,255,255)
    )
    $g.FillPath($hiBrush, $hiPath)
    $hiBrush.Dispose(); $hiPath.Dispose()
  }

  # Perforaciones de film (solo en tamaños medianos/grandes)
  if($size -ge 48){
    $holeW = [int]($size * 0.07)
    $holeH = [int]($size * 0.028)
    if($holeH -lt 3){ $holeH = 3 }
    $holeR = [int]($holeH * 0.5)
    $gap = ($size * 0.68) / 6
    $startY = [int]($size * 0.16)
    $leftX = [int]($size * 0.06)
    $rightX = [int]($size - $size*0.06 - $holeW)
    $holeColor = [System.Drawing.Color]::FromArgb(210,255,255,255)
    $holeBrush = New-Object System.Drawing.SolidBrush $holeColor
    for($i=0; $i -lt 6; $i++){
      $y = [int]($startY + $i*$gap)
      $p1 = New-RoundedRectPath $leftX $y $holeW $holeH $holeR
      $p2 = New-RoundedRectPath $rightX $y $holeW $holeH $holeR
      $g.FillPath($holeBrush, $p1); $g.FillPath($holeBrush, $p2)
      $p1.Dispose(); $p2.Dispose()
    }
    $holeBrush.Dispose()
  }

  # Sombra del play
  $cx = $size/2 + $size*0.02
  $cy = $size/2
  $w = $size * 0.34
  $h = $size * 0.42
  $p1 = New-Object System.Drawing.PointF ([float]($cx - $w*0.45), [float]($cy - $h*0.5))
  $p2 = New-Object System.Drawing.PointF ([float]($cx + $w*0.55), [float]($cy))
  $p3 = New-Object System.Drawing.PointF ([float]($cx - $w*0.45), [float]($cy + $h*0.5))
  $shadowOff = [Math]::Max(1, [int]($size*0.012))
  if($size -ge 32){
    $shadowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $shadowPath.AddPolygon(@(
      (New-Object System.Drawing.PointF ($p1.X+$shadowOff), ($p1.Y+$shadowOff)),
      (New-Object System.Drawing.PointF ($p2.X+$shadowOff), ($p2.Y+$shadowOff)),
      (New-Object System.Drawing.PointF ($p3.X+$shadowOff), ($p3.Y+$shadowOff))
    ))
    $shadowBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(55,0,0,0))
    $g.FillPath($shadowBrush, $shadowPath)
    $shadowBrush.Dispose(); $shadowPath.Dispose()
  }

  # Play blanco (triángulo con esquinas levemente redondeadas vía path)
  $playPath = New-Object System.Drawing.Drawing2D.GraphicsPath
  # Triángulo con join redondeado
  $playPath.AddPolygon(@($p1,$p2,$p3))
  # Suavizado extra: si es grande, redondeo leve de esquinas con line join
  $whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,255,255))
  $g.FillPath($whiteBrush, $playPath)
  $whiteBrush.Dispose(); $playPath.Dispose()

  # Pequeño detalle: avión de Telegram sutil en el play (solo >=128 para no saturar)
  if($size -ge 128){
    # Avión pequeño en la esquina inferior derecha del triángulo
    $planeSize = [int]($size * 0.13)
    $px = [int]($cx + $w*0.18)
    $py = [int]($cy + $h*0.22)
    # Fondo del badge circular
    $badgeR = [int]($planeSize * 0.55)
    $badgeBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,255,255))
    $g.FillEllipse($badgeBrush, ($px-$badgeR), ($py-$badgeR), ($badgeR*2), ($badgeR*2))
    $badgeBrush.Dispose()
    # Avión: path simple de papel
    $planeColor = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(14,165,233))
    $planePath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $s = $planeSize * 0.32
    $planePath.AddPolygon(@(
      (New-Object System.Drawing.PointF ($px - $s*0.9), ($py - $s*0.15)),
      (New-Object System.Drawing.PointF ($px + $s*0.9), ($py - $s*0.35)),
      (New-Object System.Drawing.PointF ($px + $s*0.35), ($py + $s*0.05)),
      (New-Object System.Drawing.PointF ($px + $s*0.55), ($py + $s*0.75)),
      (New-Object System.Drawing.PointF ($px - $s*0.25), ($py + $s*0.25)),
      (New-Object System.Drawing.PointF ($px - $s*0.55), ($py + $s*0.65))
    ))
    $g.FillPath($planeColor, $planePath)
    $planeColor.Dispose(); $planePath.Dispose()
  }

  $bgPath.Dispose()
  $g.Dispose()
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "  -> $outPath ($size x $size)" -ForegroundColor DarkGray
}

# Generar PNGs
Write-Host 'Generando íconos CanalVideos...' -ForegroundColor Cyan
$sizes = @(512,256,128,64,48,32,24,16)
foreach($s in $sizes){
  $p = Join-Path $assetsIconsDir "canalvideos-$s.png"
  Generate-Icon $s $p
}
# Copias para uso directo
Copy-Item (Join-Path $assetsIconsDir 'canalvideos-512.png') (Join-Path $root 'CanalVideos.png') -Force
Copy-Item (Join-Path $assetsIconsDir 'canalvideos-256.png') (Join-Path $root 'angular_frontend\src\assets\icon.png') -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path (Join-Path $root 'angular_frontend\src\assets') -Force | Out-Null
Copy-Item (Join-Path $assetsIconsDir 'canalvideos-256.png') (Join-Path $root 'angular_frontend\src\assets\icon.png') -Force

# Generar favicon.ico multi-res (16,24,32,48,64,256 con PNG embebido - formato ICO moderno)
function New-IcoFromPngs($pngPaths, $icoPath){
  $pngBytesList = @()
  foreach($pp in $pngPaths){
    $pngBytesList += ,([IO.File]::ReadAllBytes($pp))
  }
  $count = $pngBytesList.Count
  $fs = [IO.File]::Create($icoPath)
  $bw = New-Object IO.BinaryWriter $fs
  $bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$count)
  $offset = 6 + 16*$count
  $entries = @()
  for($i=0; $i -lt $count; $i++){
    $b = $pngBytesList[$i]
    # deducir tamaño desde nombre o desde IHDR
    $sz = 0
    if($pngPaths[$i] -match '(\d+)\.png$'){ $sz = [int]$Matches[1] }
    if($sz -eq 256){ $w=0; $h=0 } else { $w=[byte]$sz; $h=[byte]$sz }
    $entries += @{ w=$w; h=$h; size=$b.Length; offset=$offset }
    $offset += $b.Length
  }
  for($i=0; $i -lt $count; $i++){
    $e = $entries[$i]
    $bw.Write([byte]$e.w); $bw.Write([byte]$e.h)
    $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$e.size); $bw.Write([UInt32]$e.offset)
  }
  foreach($b in $pngBytesList){ $bw.Write($b) }
  $bw.Close(); $fs.Close()
  Write-Host "  -> $icoPath ($count imágenes)" -ForegroundColor Green
}

$icoPngs = @(16,24,32,48,64,256) | ForEach-Object { Join-Path $assetsIconsDir "canalvideos-$_.png" }
# Asegurar que 24 existe (no lo generamos arriba si no está en sizes? sí, 24 está)
$faviconPath = Join-Path $outDir 'favicon.ico'
New-IcoFromPngs $icoPngs $faviconPath
Copy-Item $faviconPath (Join-Path $root 'CanalVideos.ico') -Force
New-Item -ItemType Directory -Path (Join-Path $root 'angular_frontend\public') -Force | Out-Null
Copy-Item $faviconPath (Join-Path $root 'angular_frontend\public\favicon.ico') -Force -ErrorAction SilentlyContinue

Write-Host 'Listo.' -ForegroundColor Green

# Generar SVG fuente (editable)
$svg = @'
<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256" role="img">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#0F172A"/><stop offset="100%" stop-color="#0EA5E9"/>
    </linearGradient>
    <linearGradient id="hi" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="white" stop-opacity="0.18"/><stop offset="100%" stop-color="white" stop-opacity="0"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="256" height="256" rx="56" fill="url(#bg)"/>
  <rect x="1" y="1" width="254" height="122" rx="56" fill="url(#hi)"/>
  <!-- perforaciones film -->
  <g fill="white" opacity="0.82">
    <rect x="15" y="41" width="18" height="7" rx="3.5"/><rect x="223" y="41" width="18" height="7" rx="3.5"/>
    <rect x="15" y="69" width="18" height="7" rx="3.5"/><rect x="223" y="69" width="18" height="7" rx="3.5"/>
    <rect x="15" y="97" width="18" height="7" rx="3.5"/><rect x="223" y="97" width="18" height="7" rx="3.5"/>
    <rect x="15" y="125" width="18" height="7" rx="3.5"/><rect x="223" y="125" width="18" height="7" rx="3.5"/>
    <rect x="15" y="153" width="18" height="7" rx="3.5"/><rect x="223" y="153" width="18" height="7" rx="3.5"/>
    <rect x="15" y="181" width="18" height="7" rx="3.5"/><rect x="223" y="181" width="18" height="7" rx="3.5"/>
  </g>
  <!-- sombra play -->
  <path d="M 95 72 L 172 128 L 95 184 Z" fill="black" opacity="0.18"/>
  <!-- play -->
  <path d="M 93 70 L 170 128 L 93 186 Z" fill="white"/>
  <!-- badge avión (solo visible en grande) -->
  <circle cx="168" cy="168" r="18" fill="white"/>
  <path d="M 158 166 L 178 161 L 171 167 L 174 178 L 166 170 L 155 177 Z" fill="#0EA5E9"/>
</svg>
'@
$svgPath = Join-Path $assetsIconsDir 'canalvideos.svg'
Set-Content -Path $svgPath -Value $svg -Encoding UTF8
Write-Host "  -> $svgPath" -ForegroundColor DarkGray
