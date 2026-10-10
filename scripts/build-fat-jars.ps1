param(
    [ValidateSet('All', 'server-node', 'client-node', 'document-worker')]
    [string]$Project = 'All',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testArguments = @()
if ($SkipTests) { $testArguments += '-DskipTests' }

Push-Location $projectRoot
try {
    # Each project has its own POM; install the shared library before building applications.
    & mvn -f common/pom.xml install @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build common failed' }

    $projects = if ($Project -eq 'All') {
        @('server-node', 'client-node', 'document-worker')
    } else {
        @($Project)
    }

    foreach ($application in $projects) {
        & mvn -f "$application/pom.xml" clean package @testArguments
        if ($LASTEXITCODE -ne 0) { throw "Fat JAR build failed: $application" }
        Write-Host "Fat JAR: $projectRoot/$application/target/$application-1.0.0-SNAPSHOT.jar"
    }
} finally { Pop-Location }
