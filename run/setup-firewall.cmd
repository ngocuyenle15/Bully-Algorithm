@echo off
rem ---------------------------------------------------------------------------
rem  Mo firewall Windows cho cum Bully trong LAN 192.168.0.0/24.
rem  Chay bang quyen Administrator:
rem    setup-firewall.cmd server   (may 192.168.0.101..105: TCP 9000, UDP 8999)
rem    setup-firewall.cmd worker   (may 192.168.0.100: TCP 9100)
rem  Rule chi cho phep nguon trong 192.168.0.0/24. Client khong can mo cong.
rem ---------------------------------------------------------------------------
setlocal
net session >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Hay chay script nay bang quyen Administrator.
    pause
    exit /b 1
)

set "ROLE=%~1"
if /I "%ROLE%"=="server" goto server
if /I "%ROLE%"=="worker" goto worker
echo Usage: setup-firewall.cmd ^<server^|worker^>
exit /b 1

:server
powershell -NoProfile -Command ^
  "Remove-NetFirewallRule -DisplayName 'Bully-Server-TCP','Bully-Discovery-UDP' -ErrorAction SilentlyContinue;" ^
  "New-NetFirewallRule -DisplayName 'Bully-Server-TCP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 9000 -RemoteAddress 192.168.0.0/24 -Profile Any | Out-Null;" ^
  "New-NetFirewallRule -DisplayName 'Bully-Discovery-UDP' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8999 -RemoteAddress 192.168.0.0/24 -Profile Any | Out-Null"
if errorlevel 1 goto failed
echo Da mo TCP 9000 va UDP 8999 cho 192.168.0.0/24.
exit /b 0

:worker
powershell -NoProfile -Command ^
  "Remove-NetFirewallRule -DisplayName 'Bully-Worker-gRPC' -ErrorAction SilentlyContinue;" ^
  "New-NetFirewallRule -DisplayName 'Bully-Worker-gRPC' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 9100 -RemoteAddress 192.168.0.0/24 -Profile Any | Out-Null"
if errorlevel 1 goto failed
echo Da mo TCP 9100 cho 192.168.0.0/24.
exit /b 0

:failed
echo [ERROR] Khong tao duoc firewall rule.
pause
exit /b 1
