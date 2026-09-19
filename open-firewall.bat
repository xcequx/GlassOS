@echo off
echo Otwieram port 30100 w zaporze Windows (wymaga admina)...
netsh advfirewall firewall delete rule name="GlassOS Hub" >nul 2>&1
netsh advfirewall firewall add rule name="GlassOS Hub" dir=in action=allow protocol=tcp localport=30100 profile=any
if errorlevel 1 (
  echo Nie udalo sie — kliknij prawym i "Uruchom jako administrator".
  pause
  exit /b 1
)
echo OK. Hub jest dostepny w LAN: http://192.168.1.112:30100
pause
