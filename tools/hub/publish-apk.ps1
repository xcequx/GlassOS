# Copy the built APK onto the hub so the phone can self-update.
$ErrorActionPreference = "Stop"
$proj = "C:\Users\User\Desktop\Glasses"
$src = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $src)) { throw "Missing $src - build the APK first." }
$destDir = Join-Path $proj "tools\hub\static"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
$dest = Join-Path $destDir "GlassOS.apk"
Copy-Item $src $dest -Force
Copy-Item $src (Join-Path $proj "GlassOS.apk") -Force
Copy-Item $src (Join-Path ([Environment]::GetFolderPath("Desktop")) "GlassOS.apk") -Force
$sha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $dest).Length
$gradle = Get-Content (Join-Path $proj "app\build.gradle.kts") -Raw
$code = 2
$name = "1.0.1"
if ($gradle -match 'versionCode\s*=\s*(\d+)') { $code = [int]$Matches[1] }
if ($gradle -match 'versionName\s*=\s*"([^"]+)"') { $name = $Matches[1] }
$json = @"
{
  "package": "com.uxspace",
  "versionName": "$name",
  "versionCode": $code,
  "apk": "/GlassOS.apk",
  "sha256": "$sha",
  "size": $size
}
"@
[System.IO.File]::WriteAllText((Join-Path $destDir "version.json"), $json)
Write-Host "Published GlassOS $name ($code)  $([math]::Round($size/1MB,1)) MB"
Write-Host "Hub: http://192.168.1.112:30100/GlassOS.apk"
