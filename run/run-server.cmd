@echo off
setlocal
rem ---------------------------------------------------------------------------
rem  Chay server-node trong LAN 192.168.0.0/24 (topo trong README.md):
rem    ID 1..5  <->  192.168.0.101..105 : TCP 9000, UDP multicast 8999
rem  Cach dung:
rem    run-server.cmd                 tu nhan ID tu IP 192.168.0.10x cua may nay
rem    run-server.cmd 3               chay Server ID 3 (may phai co IP 192.168.0.103)
rem    run-server.cmd [3] --headless  khong mo giao dien
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "LIB=%~dp0_lib.cmd"
if not exist "%LIB%" (
    echo [ERROR] Thieu file "%LIB%". Hay chep ca thu muc run\ ^(khong chep rieng tung file^).
    goto fail
)
set "JAR=%ROOT%\server-node\target\server-node-1.0.0-SNAPSHOT.jar"

rem Tham so dau la ID neu khong bat dau bang "--".
set "NODE_ID="
rem FIRST luon co gia tri (tien to "_"): substring cua bien rong lam cmd bao loi cu phap.
set "FIRST=_%~1"
if not "%FIRST%"=="_" if not "%FIRST:~0,3%"=="_--" set "NODE_ID=%FIRST:~1%"
if defined NODE_ID (
    call "%LIB%" :check_extra %2 %3 %4 %5 %6 %7 %8 %9
) else (
    call "%LIB%" :check_extra %*
)
if errorlevel 1 goto fail

if not defined NODE_ID (
    call "%LIB%" :detect_id
    if errorlevel 1 goto fail
)
if not defined NODE_ID set "NODE_ID=%DETECTED_ID%"
if not defined NODE_ID (
    echo [ERROR] Khong tim thay IP 192.168.0.101-105 tren may nay va khong truyen ID.
    echo Usage: run-server.cmd ^<node-id 1..5^> [--headless]
    goto fail
)
call "%LIB%" :check_id "%NODE_ID%"
if errorlevel 1 goto fail

set "EXPECTED_IP=192.168.0.10%NODE_ID%"
set "CONFIG=%ROOT%\server-node\config\server-%NODE_ID%.properties"

call "%LIB%" :check_java
if errorlevel 1 goto fail
if not exist "%JAR%" (
    echo [ERROR] Server JAR not found: "%JAR%"
    echo Run scripts\build.ps1 first.
    goto fail
)
if not exist "%CONFIG%" (
    echo [ERROR] Server config not found: "%CONFIG%"
    goto fail
)
rem bind-address trong config la %EXPECTED_IP%; kiem tra som de bao loi ro rang.
call "%LIB%" :has_ip %EXPECTED_IP%
if errorlevel 1 (
    echo [ERROR] May nay khong co IP %EXPECTED_IP% ^(trang thai Preferred^) nen khong the chay Server ID %NODE_ID%.
    echo         Kiem tra DHCP reservation, xung dot IP, hoac chay dung ID cua may nay.
    goto fail
)

echo Starting Server Node %NODE_ID% on %EXPECTED_IP%:9000 ...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%" %JAVA_EXTRA%
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Server Node %NODE_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

:fail
pause
exit /b 1
