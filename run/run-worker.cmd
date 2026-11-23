@echo off
setlocal EnableDelayedExpansion
rem ---------------------------------------------------------------------------
rem  Chay document-worker (gRPC :9100). Theo README, Worker nam o 192.168.0.100;
rem  moi server/client deu cau hinh worker=192.168.0.100:9100.
rem  Cach dung: run-worker.cmd [--headless]
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "JAR=%ROOT%\document-worker\target\document-worker-1.0.0-SNAPSHOT.jar"
set "CONFIG=%ROOT%\document-worker\config\worker.properties"
set "WORKER_IP=192.168.0.100"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH. Can JDK/JRE 21 tro len.
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [ERROR] Worker JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    pause
    exit /b 1
)
if not exist "%CONFIG%" (
    echo [ERROR] Worker config not found: "%CONFIG%"
    pause
    exit /b 1
)

powershell -NoProfile -Command "if (Get-NetIPAddress -AddressFamily IPv4 -IPAddress '%WORKER_IP%' -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if errorlevel 1 (
    echo [WARN] May nay khong co IP %WORKER_IP%. Worker van chay tren 0.0.0.0:9100,
    echo        nhung phai sua "worker=" trong server-*.properties va client-*.properties
    echo        thanh IP cua may nay, neu khong server/client se khong ket noi duoc.
)

set "EXTRA="
:collect
if "%~1"=="" goto run
set "EXTRA=!EXTRA! %1"
shift
goto collect

:run
echo Starting Document Worker on port 9100...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" !EXTRA!
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Document Worker stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
