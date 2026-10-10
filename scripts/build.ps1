param([switch]$Integration, [switch]$Ui)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'check-protocol.ps1')
    foreach ($project in @('server-node', 'client-node', 'document-worker')) {
        & mvn -f "$project/pom.xml" package
        if ($LASTEXITCODE -ne 0) { throw "Build $project failed" }
    }
    if ($Integration) {
        & mvn -f document-worker/pom.xml '-Dbully.integration=true' test
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
    }
    if ($Ui) {
        foreach ($project in @('server-node', 'client-node', 'document-worker')) {
            & mvn -f "$project/pom.xml" '-Dbully.ui=true' '-Dtest=*ViewTest' test
            if ($LASTEXITCODE -ne 0) { throw "UI checks failed: $project" }
        }
    }
} finally { Pop-Location }
