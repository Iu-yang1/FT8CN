param(
    [string]$JavaHome = '',
    [string]$AndroidSdkRoot = '',
    [string]$NdkRoot = '',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$FlangPath = '',
    [string]$BoostHeaders = '',
    [string]$MsysRoot = '',
    [string]$FftwRoot = '',
    [string]$Jt9Path = '',
    [switch]$SkipAndroidBuild,
    [switch]$SkipPerformance,
    [switch]$SkipDeviceGate
)

$ErrorActionPreference = 'Stop'
$verifyScript = Join-Path $PSScriptRoot 'verify.ps1'
& $verifyScript @PSBoundParameters
exit $LASTEXITCODE
