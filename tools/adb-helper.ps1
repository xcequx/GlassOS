# Starts GlassOS privileged helper from this PC.
# Easier than pairing inside the phone app: USB cable, or Wireless Debugging pair from PC.
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$candidates = @(
    (Join-Path $Root ".android-sdk\platform-tools\adb.exe"),
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe"
)
$adb = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $adb) {
    Write-Host "Brak adb. Zainstaluj Android SDK platform-tools albo podlacz USB i sprobuj ponownie."
    exit 1
}
Write-Host "adb: $adb"

function Show-Devices {
    & $adb devices -l
}

Write-Host ""
Write-Host "1) USB (najlatwiej): telefon kablem do PC, USB debugging ON, zezwol na ten komputer."
Write-Host "2) Wireless: na telefonie Opcje programisty -> Debugowanie bezprzewodowe"
Write-Host "   -> Sparuj urzadzenie z kodem. NIE wychodz z tego okna."
Write-Host "   Na PC wpisz IP, port parowania i 6-cyfrowy kod."
Write-Host ""
Show-Devices

$mode = Read-Host "USB enter / albo P dla parowania Wi-Fi"
if ($mode -eq "P" -or $mode -eq "p") {
    $hostPort = Read-Host "IP:port parowania (np. 192.168.1.20:37123)"
    $code = Read-Host "6-cyfrowy kod"
    & $adb pair $hostPort $code
    $connect = Read-Host "IP:port debugowania z glownego ekranu Wireless debugging (nie port parowania)"
    & $adb connect $connect
    Show-Devices
}

$serial = (& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) {
    Write-Host "Brak telefonu w adb. Sprawdz kabel / parowanie."
    exit 1
}
Write-Host "Telefon: $serial"

$pathLine = & $adb -s $serial shell pm path com.uxspace
$apk = ($pathLine | Select-String "package:" | ForEach-Object { $_.Line -replace "^package:", "" } | Select-Object -First 1)
if (-not $apk) {
    Write-Host "GlassOS nie jest zainstalowany (com.uxspace). Wgraj APK na telefon."
    exit 1
}
Write-Host "APK: $apk"
Write-Host "Startuje helper (zostaw to okno otwarte)..."
& $adb -s $serial shell "CLASSPATH=$apk app_process /system/bin --nice-name=uxspace_privileged com.uxspace.privileged.PrivilegedServer"
