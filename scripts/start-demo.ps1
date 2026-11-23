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
    @{ Project = 'server-node'; Config = 'server-4' },
    @{ Project = 'server-node'; Config = 'server-5' },
    @{ Project = 'client-node'; Config = 'client-1' },
    @{ Project = 'client-node'; Config = 'client-2' },
    @{ Project = 'client-node'; Config = 'client-3' },
    @{ Project = 'client-node'; Config = 'client-4' },
    @{ Project = 'client-node'; Config = 'client-5' }
)
# Check all artifacts before opening any processes.
foreach ($app in $applications) {
    $jarPath = Join-Path $projectRoot "$($app.Project)/target/$($app.Project)-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Run scripts/build.ps1 first' }
}
# The config files describe the LAN deployment (bind-address 192.168.0.10x, port 9000 on every machine).
# This demo runs all 11 processes on ONE machine, so it must override address/port, otherwise every server
# fails with "bind-address is not assigned to a local interface" on a machine without those IPs.
$localServers = (1..5 | ForEach-Object { "$_@127.0.0.1:$(9000 + $_)" }) -join ','
function Get-Overrides($app) {
    switch -Regex ($app.Config) {
        '^worker$'     { return @('--bind-address', '127.0.0.1', '--port', '9100') }
        '^server-(\d)$' { return @('--bind-address', '127.0.0.1', '--port', "$(9000 + [int]$Matches[1])", '--worker', '127.0.0.1:9100') }
        '^client-\d$'  { return @('--bind-address', '127.0.0.1', '--servers', $localServers, '--worker', '127.0.0.1:9100') }
    }
    throw "No overrides for $($app.Config)"
}
$launchedProcesses = @()
try {
    foreach ($app in $applications) {
        $jarPath = Join-Path $projectRoot "$($app.Project)/target/$($app.Project)-1.0.0-SNAPSHOT.jar"
        $configPath = Join-Path $projectRoot "$($app.Project)/config/$($app.Config).properties"
        $launchArguments = @('-jar', "`"$jarPath`"", '--config', "`"$configPath`"") + (Get-Overrides $app)
        if ($Headless) { $launchArguments += '--headless' }
        $process = Start-Process -FilePath $javaCommand -ArgumentList $launchArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "$($app.Config).out.log") -RedirectStandardError (Join-Path $logRoot "$($app.Config).err.log")
        $launchedProcesses += $process
        Write-Host "$($app.Config): PID $($process.Id)"
    }
    # A process that dies right away (port in use, bad config) must not go unnoticed.
    Start-Sleep -Seconds 3
    $dead = @(for ($i = 0; $i -lt $launchedProcesses.Count; $i++) { if ($launchedProcesses[$i].HasExited) { $applications[$i].Config } })
    if ($dead.Count) { throw "Exited during start-up: $($dead -join ', '). See logs\<name>.err.log" }
    Write-Host 'Use Request Access in each client; Stop Server simulates coordinator failure.'
    Write-Host 'Close application windows to stop them. Headless processes can be stopped using the printed PIDs.'
} catch {
    foreach ($process in $launchedProcesses) { if (-not $process.HasExited) { $process.Kill() } }
    throw
}
