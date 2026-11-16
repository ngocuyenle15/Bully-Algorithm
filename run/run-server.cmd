@echo off
setlocal

set "ROOT=%~dp0.."
set "JAVA=%ROOT%\runtime\jdk-21.0.10\bin\java.exe"
set "JAR=%ROOT%\server-node\target\server-node-1.0.0-SNAPSHOT.jar"

set "NODE_ID=%~1"
if not defined NODE_ID set "NODE_ID=1"
set "CONFIG=%ROOT%\server-node\config\server-%NODE_ID%.properties"

if not exist "%JAVA%" (
    echo [ERROR] Java 21 not found: "%JAVA%"
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
    echo Usage: run-server.cmd ^<node-id^>
    echo Example: run-server.cmd 2
    pause
    exit /b 1
)

echo Starting Server Node %NODE_ID%...
pushd "%ROOT%"
"%JAVA%" -jar "%JAR%" --config "%CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Server Node %NODE_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
