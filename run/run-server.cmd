@echo off
setlocal EnableDelayedExpansion
rem ---------------------------------------------------------------------------
rem  Chay server-node trong LAN 192.168.0.0/24 (topo trong README.md):
rem    ID 1..5  <->  192.168.0.101..105 : TCP 9000, UDP multicast 8999
rem  Cach dung:
rem    run-server.cmd          tu nhan ID tu IP 192.168.0.10x cua may nay
rem    run-server.cmd 3        chay Server ID 3 (may phai co IP 192.168.0.103)
rem    run-server.cmd 3 --headless   (tham so thu 2 tro di duoc chuyen cho Java)
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "JAR=%ROOT%\server-node\target\server-node-1.0.0-SNAPSHOT.jar"
set "SUBNET=192.168.0"

set "NODE_ID=%~1"
if defined NODE_ID shift
if not defined NODE_ID call :detect_id
if not defined NODE_ID (
    echo [ERROR] Khong tim thay IP %SUBNET%.101-105 tren may nay va khong truyen ID.
    echo Usage: run-server.cmd ^<node-id 1..5^>
    pause
    exit /b 1
)

echo %NODE_ID%| findstr /R /X "[1-5]" >nul
if errorlevel 1 (
    echo [ERROR] Server ID khong hop le: "%NODE_ID%". Chi chap nhan 1..5.
    pause
    exit /b 1
)

set "EXPECTED_IP=%SUBNET%.10%NODE_ID%"
set "CONFIG=%ROOT%\server-node\config\server-%NODE_ID%.properties"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH. Can JDK/JRE 21 tro len.
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [ERROR] Server JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    pause
    exit /b 1
)
if not exist "%CONFIG%" (
    echo [ERROR] Server config not found: "%CONFIG%"
    pause
    exit /b 1
)

rem bind-address trong config la %EXPECTED_IP%; kiem tra som de bao loi ro rang.
call :has_ip %EXPECTED_IP%
if errorlevel 1 (
    echo [ERROR] May nay khong co IP %EXPECTED_IP% nen khong the chay Server ID %NODE_ID%.
    echo         Kiem tra DHCP reservation hoac chay dung ID cua may nay.
    pause
    exit /b 1
)

rem Gom cac tham so con lai (vd --headless) de chuyen cho Java.
set "EXTRA="
:collect
if "%~1"=="" goto run
set "EXTRA=!EXTRA! %1"
shift
goto collect

:run
echo Starting Server Node %NODE_ID% on %EXPECTED_IP%:9000 ...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" !EXTRA!
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Server Node %NODE_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

:detect_id
for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -match '^192\.168\.0\.10[1-5]$' } | Select-Object -First 1).IPAddress"`) do set "FOUND_IP=%%I"
if defined FOUND_IP set "NODE_ID=%FOUND_IP:~-1%"
if defined NODE_ID echo Detected local IP %FOUND_IP% -^> Server ID %NODE_ID%
exit /b 0

:has_ip
powershell -NoProfile -Command "if (Get-NetIPAddress -AddressFamily IPv4 -IPAddress '%~1' -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
exit /b %ERRORLEVEL%
