Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$srcPath = "C:\Users\NG\Downloads\5888584984308682721.jpg"
$resDir  = "C:\Users\NG\NarcicTub\app\src\main\res"

if (-not (Test-Path $srcPath)) { throw "source image not found: $srcPath" }
$src = [System.Drawing.Image]::FromFile($srcPath)
Write-Output ("source: {0}x{1}" -f $src.Width, $src.Height)

function New-IconPng($srcImg, $canvas, $logoRatio, $outPath) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 8, 8, 10))
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $logo = [int]([Math]::Round($canvas * $logoRatio))
    $off = [int]((($canvas - $logo) / 2))
    $g.DrawImage($srcImg, $off, $off, $logo, $logo)
    $g.Dispose()
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# adaptive foreground layers (108dp canvas; logo at 62% = safe zone)
$fg = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
foreach ($d in $fg.Keys) {
    $dir = Join-Path $resDir ("mipmap-" + $d)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    New-IconPng $src $fg[$d] 0.62 (Join-Path $dir "ic_launcher_foreground.png")
    Write-Output ("mipmap-{0}/ic_launcher_foreground.png  {1}px" -f $d, $fg[$d])
}

# legacy launcher icons (full-bleed logo)
$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($d in $legacy.Keys) {
    $dir = Join-Path $resDir ("mipmap-" + $d)
    New-IconPng $src $legacy[$d] 1.0 (Join-Path $dir "ic_launcher.png")
    Write-Output ("mipmap-{0}/ic_launcher.png  {1}px" -f $d, $legacy[$d])
}
$src.Dispose()
Write-Output "icon generation done"
