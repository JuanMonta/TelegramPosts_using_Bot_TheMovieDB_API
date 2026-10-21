# generate-icons.ps1 — CanalStudio
# Genera el ícono de la app en múltiples tamaños y arma favicon.ico
# Diseño: cuadrado redondeado azul con burbuja de publicación blanca y destello central
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
    [System.Drawing.Color]::FromArgb(46,166,255)  # #2EA6FF / acción CanalStudio
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

  # Burbuja de conversación: símbolo neutral de publicación y contenido
  $bx = [float]($size * 0.235)
  $by = [float]($size * 0.245)
  $bw = [float]($size * 0.53)
  $bh = [float]($size * 0.405)
  $br = [float]($size * 0.105)
  $bubble = New-RoundedRectPath $bx $by $bw $bh $br

  $tail = New-Object System.Drawing.Drawing2D.GraphicsPath
  $tail.AddPolygon(@(
    (New-Object System.Drawing.PointF ([float]($bx + $bw*0.28), [float]($by + $bh - 1))),
    (New-Object System.Drawing.PointF ([float]($bx + $bw*0.10), [float]($by + $bh + $size*0.13))),
    (New-Object System.Drawing.PointF ([float]($bx + $bw*0.46), [float]($by + $bh - 1)))
  ))

  # Sombra de la burbuja
  $shadowOff = [Math]::Max(1, [int]($size*0.012))
  if($size -ge 32){
    $shadowBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(55,0,0,0))
    $g.TranslateTransform($shadowOff, $shadowOff)
    $g.FillPath($shadowBrush, $bubble)
    $g.FillPath($shadowBrush, $tail)
    $g.ResetTransform()
    $shadowBrush.Dispose()
  }

  $bubbleBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,255,255))
  $g.FillPath($bubbleBrush, $bubble)
  $g.FillPath($bubbleBrush, $tail)
  $bubbleBrush.Dispose()

  # Destello central: creación, edición y contenido
  $cx = $bx + $bw/2
  $cy = $by + $bh*0.48
  $outer = [float]($size * 0.105)
  $inner = [float]($outer * 0.27)
  $spark = New-Object System.Drawing.Drawing2D.GraphicsPath
  $spark.AddPolygon(@(
    (New-Object System.Drawing.PointF ([float]$cx, [float]($cy-$outer))),
    (New-Object System.Drawing.PointF ([float]($cx+$inner), [float]($cy-$inner))),
    (New-Object System.Drawing.PointF ([float]($cx+$outer), [float]$cy)),
    (New-Object System.Drawing.PointF ([float]($cx+$inner), [float]($cy+$inner))),
    (New-Object System.Drawing.PointF ([float]$cx, [float]($cy+$outer))),
    (New-Object System.Drawing.PointF ([float]($cx-$inner), [float]($cy+$inner))),
    (New-Object System.Drawing.PointF ([float]($cx-$outer), [float]$cy)),
    (New-Object System.Drawing.PointF ([float]($cx-$inner), [float]($cy-$inner)))
  ))
  $sparkBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(14,165,233))
  $g.FillPath($sparkBrush, $spark)
  $sparkBrush.Dispose(); $spark.Dispose()
  $bubble.Dispose(); $tail.Dispose()

  $bgPath.Dispose()
  $g.Dispose()
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "  -> $outPath ($size x $size)" -ForegroundColor DarkGray
}

# Generar PNGs
Write-Host 'Generando íconos CanalStudio...' -ForegroundColor Cyan
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
      <stop offset="0%" stop-color="#0F172A"/><stop offset="100%" stop-color="#2EA6FF"/>
    </linearGradient>
    <linearGradient id="hi" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="white" stop-opacity="0.18"/><stop offset="100%" stop-color="white" stop-opacity="0"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="256" height="256" rx="56" fill="url(#bg)"/>
  <rect x="1" y="1" width="254" height="122" rx="56" fill="url(#hi)"/>
  <!-- sombra burbuja -->
  <g fill="black" opacity="0.18" transform="translate(4 4)">
    <rect x="60" y="63" width="136" height="104" rx="27"/>
    <path d="M 98 164 L 74 200 L 123 164 Z"/>
  </g>
  <!-- burbuja de publicación -->
  <rect x="60" y="63" width="136" height="104" rx="27" fill="white"/>
  <path d="M 98 164 L 74 200 L 123 164 Z" fill="white"/>
  <!-- destello central -->
  <path d="M 128 85 L 135.3 104.7 L 155 112 L 135.3 119.3 L 128 139 L 120.7 119.3 L 101 112 L 120.7 104.7 Z" fill="#0EA5E9"/>
</svg>
'@
$svgPath = Join-Path $assetsIconsDir 'canalvideos.svg'
Set-Content -Path $svgPath -Value $svg -Encoding UTF8
Write-Host "  -> $svgPath" -ForegroundColor DarkGray
