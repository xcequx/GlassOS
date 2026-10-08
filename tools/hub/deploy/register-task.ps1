# Run GlassOS Hub at Windows startup (Task Scheduler) and expose it in the tailnet.
# powershell -ExecutionPolicy Bypass -File tools\hub\deploy\register-task.ps1
$ErrorActionPreference = "Stop"
$hubDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$port = if ($env:PORT) { [int]$env:PORT } else { 30100 }

$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) { throw "Brak Pythona w PATH. Zainstaluj Python 3.11+ (python.org) z opcją 'Add to PATH'." }

New-Item -ItemType Directory -Force -Path (Join-Path $hubDir "data") | Out-Null
$envFile = Join-Path $hubDir ".env"
$example = Join-Path (Split-Path -Parent $hubDir) "ai-gateway\.env.example"
if (-not (Test-Path $envFile) -and (Test-Path $example)) {
    Copy-Item $example $envFile
    Write-Host "Utworzono $envFile - wstaw tam DEEPSEEK_API_KEY, jesli chcesz asystenta."
}

$action = New-ScheduledTaskAction -Execute $python -Argument "`"$hubDir\server.py`" --host 0.0.0.0 --port $port" -WorkingDirectory $hubDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 3650) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
Register-ScheduledTask -TaskName "GlassOS Hub" -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName "GlassOS Hub"
Write-Host "Zadanie 'GlassOS Hub' zarejestrowane i uruchomione (port $port)."

try {
    netsh advfirewall firewall add rule name="GlassOS Hub" dir=in action=allow protocol=tcp localport=$port | Out-Null
} catch { Write-Host "Nie dodano reguly zapory (uruchom jako administrator, jesli hub ma byc widoczny w LAN)." }

$ts = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $ts) {
    & $ts serve --bg $port
    Write-Host "Tailscale serve wlaczony - hub pod https://<nazwa-maszyny>.<tailnet>.ts.net"
} else {
    Write-Host "Tailscale nie znaleziony - zainstaluj z https://tailscale.com/download/windows i uruchom: tailscale serve --bg $port"
}
