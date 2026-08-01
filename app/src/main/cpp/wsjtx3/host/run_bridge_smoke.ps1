param(
    [string]$BuildDir = '',
    [ValidateSet('Debug', 'Profile', 'Release')]
    [string]$BuildType = 'Debug',
    [ValidateSet('O2', 'O3')]
    [string]$Optimization = 'O2',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
. (Join-Path $repoRoot 'scripts\toolchain-common.ps1')

if (-not $BuildDir) {
    $BuildDir = Join-Path $scriptDir ('build-' + $BuildType.ToLowerInvariant() + '-' + $Optimization.ToLowerInvariant())
}
if (-not $SkipBuild) {
    & (Join-Path $scriptDir 'build_host_probe.ps1') -BuildDir $BuildDir `
        -BuildType $BuildType -Optimization $Optimization
    if (-not $?) { throw 'Host probe build failed.' }
}

$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
$msysRoot = Find-Ft8cnDirectory -CandidateRoots $roots -RelativePatterns @('msys64') `
    -RequiredChild 'ucrt64\bin\gfortran.exe'
if (-not $msysRoot) { throw 'MSYS2 runtime not found.' }
$executable = Join-Path $BuildDir 'ft8cn_wsjtx3_bridge_smoke.exe'
if (-not (Test-Path $executable)) { throw "Host smoke executable not found: $executable" }

$oldPath = $env:PATH
Push-Location $repoRoot
try {
    $env:PATH = "$(Join-Path $msysRoot 'ucrt64\bin');$(Join-Path $msysRoot 'usr\bin');$oldPath"
    & $executable
    if ($LASTEXITCODE -ne 0) { throw "Host smoke failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:PATH = $oldPath
}
