@echo off
setlocal

set "ROOT=%~dp0.."
set "JAR=%ROOT%\client-node\target\client-node-1.0.0-SNAPSHOT.jar"

set "CLIENT_ID=%~1"
if not defined CLIENT_ID set "CLIENT_ID=1"
set "CONFIG=%ROOT%\client-node\config\client-%CLIENT_ID%.properties"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH environment variable.
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
    echo Example: run-client.cmd 2
    pause
    exit /b 1
)

echo Starting Client Node %CLIENT_ID%...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Client Node %CLIENT_ID% stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
