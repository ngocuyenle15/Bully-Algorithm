param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $mavenArguments = @('clean', 'package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Fat JAR build failed' }

    [xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw
    $finalName = ([string]$pom.project.build.finalName).Replace('${project.artifactId}', [string]$pom.project.artifactId).Replace('${project.version}', [string]$pom.project.version)
    $jarPath = Join-Path $PSScriptRoot "target/$finalName.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) { throw "Fat JAR not found: $jarPath" }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { throw 'JAR manifest missing' }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $mainClass = [string]$pom.project.properties.'app.mainClass'
        if ($manifest -notmatch ('(?m)^Main-Class:\s*' + [regex]::Escape($mainClass) + '\s*$')) {
            throw 'Executable Main-Class missing or incorrect'
        }
        foreach ($resource in @(
            ($mainClass.Replace('.', '/') + '.class'),
            'io/grpc/ManagedChannelBuilder.class',
            'com/google/protobuf/ByteString.class',
            'javafx/application/Platform.class',
            'META-INF/services/io.grpc.ManagedChannelProvider',
            'META-INF/services/io.grpc.ServerProvider'
        )) {
            if (-not $archive.GetEntry($resource)) { throw "Fat JAR dependency/resource missing: $resource" }
        }
    } finally { $archive.Dispose() }

    Write-Host "Fat JAR verified: $jarPath"
    Write-Host "Run: java -jar target/$finalName.jar --config config/<configuration>.properties"
} finally { Pop-Location }
