@echo off
setlocal

set "ROOT=%~dp0.."
set "JAVA=%ROOT%\runtime\jdk-21.0.10\bin\java.exe"
set "JAR=%ROOT%\document-worker\target\document-worker-1.0.0-SNAPSHOT.jar"
set "CONFIG=%ROOT%\document-worker\config\worker.properties"

if not exist "%JAVA%" (
    echo [ERROR] Java 21 not found: "%JAVA%"
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
"%JAVA%" -jar "%JAR%" --config "%CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Document Worker stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
