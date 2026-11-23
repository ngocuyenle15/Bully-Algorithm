@echo off
setlocal
rem ---------------------------------------------------------------------------
rem  Chay document-worker (gRPC :9100). Theo README, Worker nam o 192.168.0.100;
rem  moi server/client deu cau hinh worker=192.168.0.100:9100.
rem  Cach dung: run-worker.cmd [--headless]
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "LIB=%~dp0_lib.cmd"
if not exist "%LIB%" (
    echo [ERROR] Thieu file "%LIB%". Hay chep ca thu muc run\ ^(khong chep rieng tung file^).
    goto fail
)
set "JAR=%ROOT%\document-worker\target\document-worker-1.0.0-SNAPSHOT.jar"
set "CONFIG=%ROOT%\document-worker\config\worker.properties"
set "WORKER_IP=192.168.0.100"

call "%LIB%" :check_extra %*
if errorlevel 1 goto fail
call "%LIB%" :check_java
if errorlevel 1 goto fail
if not exist "%JAR%" (
    echo [ERROR] Worker JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    goto fail
)
if not exist "%CONFIG%" (
    echo [ERROR] Worker config not found: "%CONFIG%"
    goto fail
)
call "%LIB%" :has_ip %WORKER_IP%
if errorlevel 1 (
    echo [WARN] May nay khong co IP %WORKER_IP%. Worker van lang nghe 0.0.0.0:9100,
    echo        nhung phai sua "worker=" trong server-*.properties va client-*.properties
    echo        thanh IP cua may nay, neu khong server/client se khong ket noi duoc.
)

echo Starting Document Worker on port 9100...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" %JAVA_EXTRA%
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Document Worker stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

:fail
pause
exit /b 1
