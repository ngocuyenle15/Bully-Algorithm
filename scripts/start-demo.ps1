param([switch]$Headless)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$javaCommand = (Get-Command java).Source
$applications = @(
    @{ Project = 'document-worker'; Config = 'worker' },
    @{ Project = 'server-node'; Config = 'server-1' },
    @{ Project = 'server-node'; Config = 'server-2' },
    @{ Project = 'server-node'; Config = 'server-3' },
    @{ Project = 'client-node'; Config = 'client-1' },
    @{ Project = 'client-node'; Config = 'client-2' },
    @{ Project = 'client-node'; Config = 'client-3' }
)
# Check all artifacts before opening any processes.
foreach ($app in $applications) {
    $jarPath = Join-Path $projectRoot "$($app.Project)/target/$($app.Project)-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Run scripts/build.ps1 first' }
}
$launchedProcesses = @()
try {
    foreach ($app in $applications) {
        $jarPath = Join-Path $projectRoot "$($app.Project)/target/$($app.Project)-1.0.0-SNAPSHOT.jar"
        $configPath = Join-Path $projectRoot "$($app.Project)/config/$($app.Config).properties"
        $launchArguments = @('-jar', "`"$jarPath`"", '--config', "`"$configPath`"")
        if ($Headless) { $launchArguments += '--headless' }
        $process = Start-Process -FilePath $javaCommand -ArgumentList $launchArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "$($app.Config).out.log") -RedirectStandardError (Join-Path $logRoot "$($app.Config).err.log")
        $launchedProcesses += $process
        Write-Host "$($app.Config): PID $($process.Id)"
    }
    Write-Host 'Use Request Access in each client; Stop Server simulates coordinator failure.'
    Write-Host 'Close application windows to stop them. Headless processes can be stopped using the printed PIDs.'
} catch {
    foreach ($process in $launchedProcesses) { if (-not $process.HasExited) { $process.Kill() } }
    throw
}
