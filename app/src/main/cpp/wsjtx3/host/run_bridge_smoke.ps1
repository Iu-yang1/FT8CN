$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')

$env:PATH = 'H:\tools\msys64\ucrt64\bin;H:\tools\msys64\usr\bin;' + $env:PATH

Push-Location $repoRoot
try {
    & (Join-Path $scriptDir 'build_host_probe.ps1')
    & (Join-Path $scriptDir 'build\ft8cn_wsjtx3_bridge_smoke.exe')
}
finally {
    Pop-Location
}
