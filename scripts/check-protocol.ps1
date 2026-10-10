$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$expected = $null
foreach ($application in @('server-node', 'client-node', 'document-worker')) {
    $path = Join-Path $projectRoot "$application/src/main/proto/document.proto"
    $protocol = Get-Content -LiteralPath $path -Raw
    # Java packages differ deliberately; the wire package, RPCs and field numbers must match.
    $normalized = ($protocol -replace 'option java_package\s*=\s*"[^"]+";', '' -replace '\r\n', "`n").Trim()
    if ($null -eq $expected) { $expected = $normalized }
    elseif ($normalized -ne $expected) { throw "Protocol differs: $application" }
}
Write-Host 'Protocol contract matches across all three standalone projects.'
