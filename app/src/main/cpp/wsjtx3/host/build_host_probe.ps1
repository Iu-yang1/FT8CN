param(
    [string]$BuildDir = '',
    [ValidateSet('Debug', 'Profile', 'Release')]
    [string]$BuildType = 'Debug',
    [ValidateSet('O2', 'O3')]
    [string]$Optimization = 'O2',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$MsysRoot = '',
    [string]$FftwRoot = ''
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
. (Join-Path $repoRoot 'scripts\toolchain-common.ps1')
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)

if (-not $MsysRoot) {
    $MsysRoot = Find-Ft8cnDirectory -ExplicitPath '' -CandidateRoots $roots `
        -RelativePatterns @('msys64') -RequiredChild 'ucrt64\bin\gfortran.exe'
}
if (-not $MsysRoot) {
    throw 'MSYS2 UCRT64 toolchain not found. Set -MsysRoot or MSYS2_ROOT.'
}
$ucrtBin = Join-Path $MsysRoot 'ucrt64\bin'
$msysBin = Join-Path $MsysRoot 'usr\bin'
if (-not $CMakePath) { $CMakePath = Join-Path $ucrtBin 'cmake.exe' }
if (-not $NinjaPath) { $NinjaPath = Join-Path $ucrtBin 'ninja.exe' }
if (-not $FftwRoot) { $FftwRoot = Join-Path $MsysRoot 'ucrt64' }
if (-not $BuildDir) { $BuildDir = Join-Path $scriptDir ('build-' + $BuildType.ToLowerInvariant() + '-' + $Optimization.ToLowerInvariant()) }

foreach ($required in @($CMakePath, $NinjaPath, (Join-Path $FftwRoot 'include\fftw3.h'))) {
    if (-not (Test-Path $required)) { throw "Required host tool/file not found: $required" }
}

$oldPath = $env:PATH
try {
    $env:PATH = "$ucrtBin;$msysBin;$oldPath"
    & $CMakePath -S $scriptDir -B $BuildDir -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$NinjaPath" `
        "-DCMAKE_BUILD_TYPE=$BuildType" `
        "-DFT8CN_RELEASE_OPT_LEVEL=$Optimization" `
        "-DWSJTX3_FFTW_ROOT=$FftwRoot"
    if ($LASTEXITCODE -ne 0) { throw 'Host CMake configure failed.' }
    & $CMakePath --build $BuildDir
    if ($LASTEXITCODE -ne 0) { throw 'Host build failed.' }
} finally {
    $env:PATH = $oldPath
}
