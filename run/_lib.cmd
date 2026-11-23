@echo off
rem ---------------------------------------------------------------------------
rem  Thu vien dung chung cho run-*.cmd (khong chay truc tiep).
rem  Goi:  call "%~dp0_lib.cmd" :<ham> [tham so...]
rem  Khong dung EnableDelayedExpansion: duong dan chua "!" se bi hong.
rem ---------------------------------------------------------------------------
set "_LIB_FN=%~1"
if not defined _LIB_FN (
    echo [ERROR] _lib.cmd la thu vien, hay chay run-server.cmd / run-client.cmd / run-worker.cmd.
    exit /b 2
)
shift /1
goto %_LIB_FN%

rem --- Java 21+ phai co trong PATH -------------------------------------------
:check_java
where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Khong tim thay java trong PATH. Can Java 21 tro len.
    exit /b 1
)
set "JAVA_SPEC="
set "JAVA_MAJOR="
for /f "tokens=2 delims==" %%V in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.specification.version"') do set "JAVA_SPEC=%%V"
if not defined JAVA_SPEC (
    echo [ERROR] Khong xac dinh duoc phien ban Java.
    exit /b 1
)
set "JAVA_SPEC=%JAVA_SPEC: =%"
for /f "delims=." %%M in ("%JAVA_SPEC%") do set "JAVA_MAJOR=%%M"
set /a JAVA_MAJOR_NUM=JAVA_MAJOR 2>nul
if %JAVA_MAJOR_NUM% LSS 21 (
    echo [ERROR] Can Java 21 tro len, java trong PATH la phien ban %JAVA_SPEC%.
    exit /b 1
)
exit /b 0

rem --- Kiem tra may co IPv4 %1 o trang thai Preferred ----------------------
rem  (Duplicate/Tentative = Windows phat hien trung IP, dia chi khong dung duoc)
:has_ip
powershell -NoProfile -Command "if (Get-NetIPAddress -AddressFamily IPv4 -IPAddress '%~1' -AddressState Preferred -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
exit /b %ERRORLEVEL%

rem --- Suy ra so node 1..5 tu IP 192.168.0.101..105 --------------------------
rem  Ket qua: DETECTED_ID (rong neu khong co). Loi neu may co nhieu IP phu hop.
:detect_id
set "DETECTED_ID="
set "DETECTED_IP="
set "DETECTED_COUNT=0"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -match '^192\.168\.0\.10[1-5]$' } | ForEach-Object { $_.IPAddress }"`) do (
    set /a DETECTED_COUNT+=1
    set "DETECTED_IP=%%I"
)
if %DETECTED_COUNT% GTR 1 (
    echo [ERROR] May co nhieu IP trong 192.168.0.101-105; hay truyen ID ro rang.
    exit /b 1
)
if %DETECTED_COUNT%==1 set "DETECTED_ID=%DETECTED_IP:~-1%"
if defined DETECTED_ID echo Detected local IP %DETECTED_IP% -^> ID %DETECTED_ID%
exit /b 0

rem --- ID hop le: dung mot trong 1 2 3 4 5 (khong dung echo/findstr) --------
:check_id
set "ID_OK="
for %%N in (1 2 3 4 5) do if "%~1"=="%%N" set "ID_OK=1"
if not defined ID_OK (
    echo [ERROR] ID khong hop le: "%~1". Chi chap nhan 1..5.
    exit /b 1
)
exit /b 0

rem --- Tham so them: chi cho phep --headless --------------------------------
rem  Cac option khac (--id, --bind-address, --port, --worker...) se ghi de config
rem  va lach qua buoc kiem tra ID/IP, nen bi tu choi.
:check_extra
set "JAVA_EXTRA="
:check_extra_loop
if "%~1"=="" exit /b 0
if /I "%~1"=="--headless" (
    set "JAVA_EXTRA=--headless"
    shift /1
    goto check_extra_loop
)
echo [ERROR] Tham so khong duoc ho tro: %1  (chi chap nhan --headless; sua cau hinh trong file .properties)
exit /b 1
