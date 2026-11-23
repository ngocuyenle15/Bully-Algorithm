@echo off
setlocal
rem ---------------------------------------------------------------------------
rem  Chay client-node. Client khong mo cong lang nghe (local-port=0) nen chay
rem  chung may voi server-node khong xung dot cong.
rem  Cach dung:
rem    run-client.cmd                 tu nhan so client tu IP 192.168.0.10x (x=1..5)
rem    run-client.cmd 2               chay client-2 (co the chay tren bat ky may nao)
rem    run-client.cmd [2] --headless
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "LIB=%~dp0_lib.cmd"
if not exist "%LIB%" (
    echo [ERROR] Thieu file "%LIB%". Hay chep ca thu muc run\ ^(khong chep rieng tung file^).
    goto fail
)
set "JAR=%ROOT%\client-node\target\client-node-1.0.0-SNAPSHOT.jar"

set "CLIENT_ID="
rem FIRST luon co gia tri (tien to "_"): substring cua bien rong lam cmd bao loi cu phap.
set "FIRST=_%~1"
if not "%FIRST%"=="_" if not "%FIRST:~0,3%"=="_--" set "CLIENT_ID=%FIRST:~1%"
if defined CLIENT_ID (
    call "%LIB%" :check_extra %2 %3 %4 %5 %6 %7 %8 %9
) else (
    call "%LIB%" :check_extra %*
)
if errorlevel 1 goto fail

if not defined CLIENT_ID (
    call "%LIB%" :detect_id
    if errorlevel 1 goto fail
)
if not defined CLIENT_ID set "CLIENT_ID=%DETECTED_ID%"
if not defined CLIENT_ID (
    echo [ERROR] Khong tim thay IP 192.168.0.101-105 tren may nay va khong truyen so client.
    echo Usage: run-client.cmd ^<client-number 1..5^> [--headless]
    goto fail
)
call "%LIB%" :check_id "%CLIENT_ID%"
if errorlevel 1 goto fail

set "CONFIG=%ROOT%\client-node\config\client-%CLIENT_ID%.properties"

call "%LIB%" :check_java
if errorlevel 1 goto fail
if not exist "%JAR%" (
    echo [ERROR] Client JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    goto fail
)
if not exist "%CONFIG%" (
    echo [ERROR] Client config not found: "%CONFIG%"
    goto fail
)

echo Starting Client Node %CLIENT_ID%...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" %JAVA_EXTRA%
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Client Node %CLIENT_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

:fail
pause
exit /b 1
