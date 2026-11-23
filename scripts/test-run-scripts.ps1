# Kiem thu cac script run\*.cmd + mutation test cho tung guard.
#   powershell -ExecutionPolicy Bypass -File .\scripts\test-run-scripts.ps1
# Chay trong sandbox (thu muc tam co "!" trong ten) voi java gia; khong dung JAR/Java that,
# khong can quyen Administrator, khong thay doi file trong repo.
# Vi may dev khong co IP 192.168.0.10x, cac case "chay thanh cong" doi subnet trong BAN SAO
# script sang subnet that cua may (IP co octet cuoi 1..5). Khong co IP nhu vay -> SKIP (NOT-VERIFIED).
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$sandbox = Join-Path $env:TEMP ("bully run!test-" + [guid]::NewGuid().ToString('N').Substring(0, 6))
$fakeBin = Join-Path $sandbox 'fakebin'
$results = New-Object System.Collections.Generic.List[object]

function New-Sandbox {
    New-Item -ItemType Directory -Force -Path "$sandbox\run", $fakeBin | Out-Null
    foreach ($p in 'server-node', 'client-node', 'document-worker') {
        New-Item -ItemType Directory -Force -Path "$sandbox\$p\target", "$sandbox\$p\config" | Out-Null
        Set-Content -Path "$sandbox\$p\target\$p-1.0.0-SNAPSHOT.jar" -Value '' -Encoding ascii
    }
    1..5 | ForEach-Object {
        Set-Content "$sandbox\server-node\config\server-$_.properties" '' -Encoding ascii
        Set-Content "$sandbox\client-node\config\client-$_.properties" '' -Encoding ascii
    }
    Set-Content "$sandbox\document-worker\config\worker.properties" '' -Encoding ascii
    Set-Content "$fakeBin\java.cmd" -Encoding ascii -Value @'
@echo off
if "%~1"=="-XshowSettings:properties" (
    echo     java.specification.version = %FAKE_JAVA_SPEC%
    exit /b 0
)
echo FAKEJAVA %*
exit /b 0
'@
}

# Ap dung baseRewrite (doi subnet) TRUOC, roi moi den $rewrite (mutation).
function Reset-Scripts([hashtable]$rewrite) {
    foreach ($f in Get-ChildItem "$repo\run\*.cmd") {
        $text = [IO.File]::ReadAllText($f.FullName)
        foreach ($map in @($script:baseRewrite, $rewrite)) {
            if ($map) { foreach ($k in $map.Keys) { $text = $text.Replace($k, $map[$k]) } }
        }
        [IO.File]::WriteAllText("$sandbox\run\$($f.Name)", $text)
    }
}

function Invoke-Script([string]$script, [string]$arguments, [string]$javaSpec = '21', [switch]$NoJava) {
    $path = if ($NoJava) { '' } else { "$fakeBin;" }
    $wrapper = Join-Path $sandbox 'wrapper.cmd'
    Set-Content $wrapper -Encoding ascii -Value @"
@echo off
set "PATH=$path%SystemRoot%\System32;%SystemRoot%;%SystemRoot%\System32\WindowsPowerShell\v1.0"
set "FAKE_JAVA_SPEC=$javaSpec"
call "$sandbox\run\$script" $arguments < nul
echo EXIT=%ERRORLEVEL%
"@
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # stderr cua cmd khong phai loi cua harness
    try { $out = (& cmd.exe /d /c "`"$wrapper`"" 2>&1 | ForEach-Object { "$_" } | Out-String) }
    finally { $ErrorActionPreference = $previous }
    $code = if ($out -match 'EXIT=(-?\d+)') { [int]$Matches[1] } else { -999 }
    [pscustomobject]@{ Code = $code; Out = $out }
}

function Check([string]$name, $r, [int]$code, [string]$must, [string]$mustNot) {
    $ok = ($r.Code -eq $code) -and -not $r.Out.Contains('syntax of the command is incorrect') -and (-not $must -or $r.Out.Contains($must)) -and (-not $mustNot -or -not $r.Out.Contains($mustNot))
    $results.Add([pscustomobject]@{ Case = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Exit = $r.Code })
    if (-not $ok) { Write-Host "---- $name output:`n$($r.Out)" }
}

# Mutation: ap dung thay doi xau vao ban sao, case tuong ung PHAI fail ("killed").
function Mutate([string]$name, [hashtable]$rewrite, [scriptblock]$case) {
    Reset-Scripts $rewrite
    $before = $results.Count
    & $case
    $last = $results[$results.Count - 1]
    $results.RemoveAt($results.Count - 1)
    $killed = $last.Result -eq 'FAIL'
    $results.Add([pscustomobject]@{ Case = "MUTANT $name"; Result = $(if ($killed) { 'KILLED' } else { 'SURVIVED' }); Exit = $last.Exit })
    Reset-Scripts @{}
}

$script:baseRewrite = @{}
New-Sandbox
$local = Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(\d+\.\d+\.\d+)\.([1-5])$' -and $_.IPAddress -notlike '127.*' } | Select-Object -First 1
if ($local) {
    $null = $local.IPAddress -match '^(\d+\.\d+\.\d+)\.([1-5])$'
    $prefix = $Matches[1]; $localId = $Matches[2]
    $script:baseRewrite = @{
        '192.168.0.10%NODE_ID%' = "$prefix.%NODE_ID%"
        "'^192\.168\.0\.10[1-5]$'" = "'^" + [regex]::Escape($prefix) + "\.[1-5]$'"
    }
} else { $script:baseRewrite = @{}; $localId = $null }
Reset-Scripts @{}
$other = if ($localId -eq '4') { '5' } else { '4' }

# ---------------- run-server.cmd
foreach ($bad in '9', '0', '03', '" 3"', 'abc', '-1', '3x') {
    Check "server rejects ID $bad" (Invoke-Script 'run-server.cmd' $bad) 1 '' 'FAKEJAVA'
}
Check 'server: injection in ID is not executed' (Invoke-Script 'run-server.cmd' '"1&echo INJECTED_LINE"') 1 'ID khong hop le' 'FAKEJAVA'
Check 'server rejects --id override (2nd arg)' (Invoke-Script 'run-server.cmd' '3 --id 5') 1 'khong duoc ho tro' 'FAKEJAVA'
Check 'server rejects --bind-address override' (Invoke-Script 'run-server.cmd' '--bind-address 0.0.0.0') 1 'khong duoc ho tro' 'FAKEJAVA'
Check 'server rejects Java 17' (Invoke-Script 'run-server.cmd' '3' '17') 1 'Can Java 21' 'FAKEJAVA'
Check 'server rejects Java 1.8' (Invoke-Script 'run-server.cmd' '3' '1.8') 1 'Can Java 21' 'FAKEJAVA'
Check 'server rejects missing java' (Invoke-Script 'run-server.cmd' '3' -NoJava) 1 'Khong tim thay java' 'FAKEJAVA'
Check "server rejects ID $other without matching IP" (Invoke-Script 'run-server.cmd' $other) 1 'khong co IP' 'FAKEJAVA'
if ($localId) {
    Check 'server: auto-detect ID (path contains !)' (Invoke-Script 'run-server.cmd' '') 0 "server-$localId.properties" ''
    Check 'server: explicit ID' (Invoke-Script 'run-server.cmd' $localId) 0 "server-$localId.properties" ''
    Check 'server: --headless only (auto ID)' (Invoke-Script 'run-server.cmd' '--headless') 0 '--headless' ''
    Check 'server: ID + --HEADLESS (case)' (Invoke-Script 'run-server.cmd' "$localId --HEADLESS") 0 'properties" --headless' ''
    Check 'server: empty arg "" = auto-detect' (Invoke-Script 'run-server.cmd' '""') 0 "server-$localId.properties" ''
    Check 'server: Java 25 accepted' (Invoke-Script 'run-server.cmd' $localId '25') 0 'FAKEJAVA' ''
}
# ---------------- run-client.cmd
Check 'client rejects ID 7' (Invoke-Script 'run-client.cmd' '7') 1 '' 'FAKEJAVA'
Check 'client rejects --worker override' (Invoke-Script 'run-client.cmd' '2 --worker 1.2.3.4:1') 1 'khong duoc ho tro' 'FAKEJAVA'
Check 'client: explicit ID on any machine' (Invoke-Script 'run-client.cmd' '2') 0 'client-2.properties' ''
if ($localId) { Check 'client: auto-detect ID' (Invoke-Script 'run-client.cmd' '') 0 "client-$localId.properties" '' }
# ---------------- run-worker.cmd
Check 'worker rejects --port override' (Invoke-Script 'run-worker.cmd' '--port 1') 1 'khong duoc ho tro' 'FAKEJAVA'
Check 'worker warns when IP is not 192.168.0.100' (Invoke-Script 'run-worker.cmd' '') 0 '[WARN]' ''
Check 'worker rejects Java 17' (Invoke-Script 'run-worker.cmd' '' '17') 1 'Can Java 21' 'FAKEJAVA'
# ---------------- _lib.cmd / setup-firewall.cmd (khong can admin)
Check 'lib refuses direct run' (Invoke-Script '_lib.cmd' '') 2 'thu vien' ''
Check 'firewall: missing role' (Invoke-Script 'setup-firewall.cmd' '') 1 'Usage' ''
Check 'firewall: unknown role' (Invoke-Script 'setup-firewall.cmd' 'servers') 1 'Usage' ''
Check 'firewall: extra argument' (Invoke-Script 'setup-firewall.cmd' 'SERVER x') 1 'Thua tham so' ''

# ---------------- mutation tests (moi guard phai lam it nhat 1 case fail)
Mutate 'check_extra removed' @{ 'call "%LIB%" :check_extra %2 %3 %4 %5 %6 %7 %8 %9' = 'rem' } {
    Check 'm' (Invoke-Script 'run-server.cmd' '3 --id 5') 1 'khong duoc ho tro' 'FAKEJAVA' }
Mutate 'check_id removed' @{ 'call "%LIB%" :check_id "%NODE_ID%"' = 'rem' } {
    Check 'm' (Invoke-Script 'run-server.cmd' '9') 1 'ID khong hop le' '' }
Mutate 'java version check removed' @{ 'if %JAVA_MAJOR_NUM% LSS 21 (' = 'if 0==1 (' } {
    Check 'm' (Invoke-Script 'run-server.cmd' '3' '17') 1 'Can Java 21' 'FAKEJAVA' }
Mutate 'where java removed' @{ 'where java >nul 2>nul' = 'ver >nul' } {
    Check 'm' (Invoke-Script 'run-server.cmd' '3' -NoJava) 1 'Khong tim thay java' '' }
Mutate 'has_ip check removed' @{ 'call "%LIB%" :has_ip %EXPECTED_IP%' = 'ver >nul' } {
    Check 'm' (Invoke-Script 'run-server.cmd' $other) 1 'khong co IP' 'FAKEJAVA' }
Mutate 'jar existence check removed' @{ 'if not exist "%JAR%" (' = 'if 0==1 (' } {
    Remove-Item "$sandbox\server-node\target\server-node-1.0.0-SNAPSHOT.jar"
    try { Check 'm' (Invoke-Script 'run-server.cmd' '3') 1 'JAR not found' 'FAKEJAVA' }
    finally { Set-Content "$sandbox\server-node\target\server-node-1.0.0-SNAPSHOT.jar" '' -Encoding ascii } }
Remove-Item "$sandbox\run\_lib.cmd"
Check 'server: missing _lib.cmd is explained' (Invoke-Script 'run-server.cmd' '3') 1 'Thieu file' 'FAKEJAVA'
Check 'worker: missing _lib.cmd is explained' (Invoke-Script 'run-worker.cmd' '') 1 'Thieu file' 'FAKEJAVA'
Reset-Scripts @{}
Mutate 'missing-_lib guard removed' @{ 'if not exist "%LIB%" (' = 'if 0==1 (' } {
    Remove-Item "$sandbox\run\_lib.cmd"
    Check 'm' (Invoke-Script 'run-server.cmd' '3') 1 'Thieu file' '' }
Mutate 'firewall extra-arg check removed' @{ 'if not "%~2"=="" (' = 'if 0==1 (' } {
    Check 'm' (Invoke-Script 'setup-firewall.cmd' 'SERVER x') 1 'Thua tham so' '' }
if ($localId) {
    $delayed = @{}; $delayed['@echo off' + "`r`n" + 'setlocal' + "`r`n"] = '@echo off' + "`r`n" + 'setlocal EnableDelayedExpansion' + "`r`n"
    Mutate 'EnableDelayedExpansion re-added (path with !)' $delayed {
        Check 'm' (Invoke-Script 'run-server.cmd' '') 0 "server-$localId.properties" '' }
    $multi = @{}
    $multi["'^" + [regex]::Escape($prefix) + "\.[1-5]$'"] = "'.'"   # moi IP deu khop -> nhieu IP
    Reset-Scripts $multi
    Check 'detect refuses several matching IPs' (Invoke-Script 'run-server.cmd' '') 1 'nhieu IP' 'FAKEJAVA'
    $multiMutant = $multi.Clone(); $multiMutant['if %DETECTED_COUNT% GTR 1 ('] = 'if 0==1 ('
    Mutate 'multi-IP guard removed' $multiMutant {
        Check 'm' (Invoke-Script 'run-server.cmd' '') 1 'nhieu IP' 'FAKEJAVA' }
    Mutate 'auto-detect removed' @{ 'call "%LIB%" :detect_id' = 'ver >nul' } {
        Check 'm' (Invoke-Script 'run-server.cmd' '') 0 "server-$localId.properties" '' }
}

Remove-Item -Recurse -Force $sandbox
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
if (-not $localId) { Write-Host 'NOT-VERIFIED: positive-path cases skipped (no local IPv4 ending in .1-.5).' }
$bad = @($results | Where-Object { $_.Result -in 'FAIL', 'SURVIVED' })
Write-Host ("cases={0} failed/survived={1}" -f $results.Count, $bad.Count)
if ($bad.Count) { exit 1 }
