param([switch]$Build, [int]$Port = 8083)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# Never silently terminate a process belonging to Eclipse or another application.
$probe = New-Object System.Net.Sockets.TcpClient
$occupied = $false
try { $probe.Connect('127.0.0.1', $Port); $occupied = $true }
catch [System.Net.Sockets.SocketException] { }
finally { $probe.Dispose() }
if ($occupied) { throw "Port $Port is already in use. Stop the existing POS server before starting another one." }

Push-Location $projectRoot
try {
    $artifact = Join-Path $projectRoot 'build\libs\drinkshop-pos-backend-0.0.1-SNAPSHOT.jar'
    if ($Build -or -not (Test-Path -LiteralPath $artifact)) {
        & .\gradlew.bat --gradle-user-home "$env:USERPROFILE\.gradle" bootJar
        if ($LASTEXITCODE -ne 0) { throw 'POS build failed.' }
    }
    # Run an immutable copy. A later bootJar build cannot corrupt an active JVM's JAR.
    $runId = (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $runDirectory = Join-Path $projectRoot "build\runtime\$runId"
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $runtimeJar = Join-Path $runDirectory 'drinkshop-pos.jar'
    Copy-Item -LiteralPath $artifact -Destination $runtimeJar
    & java -jar $runtimeJar "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw 'POS server exited with an error.' }
}
finally { Pop-Location }
