@echo off
setlocal EnableDelayedExpansion
rem ---------------------------------------------------------------------------
rem  Chay client-node. Client khong mo cong lang nghe (local-port=0) nen chay
rem  chung may voi server-node khong xung dot cong.
rem  Cach dung:
rem    run-client.cmd          tu nhan so client tu IP 192.168.0.10x (x=1..5)
rem    run-client.cmd 2        chay client-2 (co the chay tren bat ky may nao)
rem    run-client.cmd 2 --headless
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "JAR=%ROOT%\client-node\target\client-node-1.0.0-SNAPSHOT.jar"

set "CLIENT_ID=%~1"
if defined CLIENT_ID shift
if not defined CLIENT_ID call :detect_id
if not defined CLIENT_ID (
    echo [ERROR] Khong tim thay IP 192.168.0.101-105 tren may nay va khong truyen so client.
    echo Usage: run-client.cmd ^<client-number 1..5^>
    pause
    exit /b 1
)

set "CONFIG=%ROOT%\client-node\config\client-%CLIENT_ID%.properties"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH. Can JDK/JRE 21 tro len.
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [ERROR] Client JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    pause
    exit /b 1
)
if not exist "%CONFIG%" (
    echo [ERROR] Client config not found: "%CONFIG%"
    echo Usage: run-client.cmd ^<client-number^>
    pause
    exit /b 1
)

set "EXTRA="
:collect
if "%~1"=="" goto run
set "EXTRA=!EXTRA! %1"
shift
goto collect

:run
echo Starting Client Node %CLIENT_ID%...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" !EXTRA!
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Client Node %CLIENT_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

:detect_id
for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -match '^192\.168\.0\.10[1-5]$' } | Select-Object -First 1).IPAddress"`) do set "FOUND_IP=%%I"
if defined FOUND_IP set "CLIENT_ID=%FOUND_IP:~-1%"
if defined CLIENT_ID echo Detected local IP %FOUND_IP% -^> client-%CLIENT_ID%
exit /b 0
