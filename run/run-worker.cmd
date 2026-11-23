@echo off
setlocal

set "ROOT=%~dp0.."
set "JAR=%ROOT%\document-worker\target\document-worker-1.0.0-SNAPSHOT.jar"
set "CONFIG=%ROOT%\document-worker\config\worker.properties"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH environment variable.
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

echo Starting Document Worker...
pushd "%ROOT%"
java -jar "%JAR%" --config "%CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Document Worker stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
