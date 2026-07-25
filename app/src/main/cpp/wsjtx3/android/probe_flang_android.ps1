param(
    [string] $FlangPath = '',
    [string] $ReadObjPath = '',
    [string] $LlcPath = '',
    [string] $NdkRoot = '',
    [string] $MsysRoot = '',
    [string] $Target = 'aarch64-linux-android21',
    [switch] $EnableLlvmFallback
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
. (Join-Path $repoRoot 'scripts\toolchain-common.ps1')
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)

$FlangPath = Find-Ft8cnExecutable -ExplicitPath $FlangPath `
    -CommandNames @('flang.exe', 'flang-new.exe', 'flang-new', 'flang') `
    -CandidateRoots $roots -RelativePatterns @(
    'build\llvm-flang-*\bin\flang.exe', 'llvm*\bin\flang*.exe',
    'msys64\ucrt64\bin\flang-new.exe')
$NdkRoot = Find-Ft8cnDirectory -ExplicitPath $NdkRoot -CandidateRoots $roots `
    -RelativePatterns @('ndk\*', 'AndroidSDKLIB\ndk\*') `
    -RequiredChild 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe'
if (-not $ReadObjPath -and $NdkRoot) {
    $ReadObjPath = Join-Path $NdkRoot `
        'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe'
}
$ReadObjPath = Find-Ft8cnExecutable -ExplicitPath $ReadObjPath `
    -CommandNames @('llvm-readobj.exe', 'llvm-readobj') -CandidateRoots $roots `
    -RelativePatterns @('ndk\*\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe')
$LlcPath = Find-Ft8cnExecutable -ExplicitPath $LlcPath `
    -CommandNames @('llc.exe', 'llc') -CandidateRoots $roots `
    -RelativePatterns @('msys64\ucrt64\bin\llc.exe', 'llvm*\bin\llc.exe')
$MsysRoot = Find-Ft8cnDirectory -ExplicitPath $MsysRoot -CandidateRoots $roots `
    -RelativePatterns @('msys64') -RequiredChild 'ucrt64\bin'
$probeRoot = Join-Path $repoRoot '.tmp_flang_android_probe'
$modDir = Join-Path $probeRoot 'mod'
$objDir = Join-Path $probeRoot 'obj'
$logDir = Join-Path $probeRoot 'logs'

$flang = $FlangPath
$readObj = $ReadObjPath
$llc = $LlcPath
$target = $Target

if (-not (Test-Path $flang)) {
    throw "Missing flang: $flang"
}

if (-not (Test-Path $readObj)) {
    throw "Missing llvm-readobj: $readObj"
}

if ($EnableLlvmFallback -and -not (Test-Path $llc)) {
    throw "Missing llc: $llc"
}

New-Item -ItemType Directory -Force -Path $probeRoot, $modDir, $objDir, $logDir | Out-Null

if ($MsysRoot) {
    $env:PATH = (Join-Path $MsysRoot 'ucrt64\bin') + ';' +
        (Join-Path $MsysRoot 'usr\bin') + ';' + $env:PATH
}

$commonArgs = @(
    "--target=$target",
    '-module-dir', $modDir,
    '-I', 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib',
    '-I', 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib/77bit'
)

$compileArgs = @($commonArgs + '-c')
$emitLlvmArgs = @($commonArgs + '-S' + '-emit-llvm')

function Invoke-FlangProbe {
    param(
        [string] $Name,
        [string] $SourcePath
    )

    $objectPath = Join-Path $objDir ($Name + '.o')
    $llvmIrPath = Join-Path $objDir ($Name + '.ll')
    $logPath = Join-Path $logDir ($Name + '.log')
    $resolvedSource = (Resolve-Path $SourcePath).ProviderPath

    Write-Host "probe => $Name"
    Remove-Item $objectPath -Force -ErrorAction SilentlyContinue
    Remove-Item $llvmIrPath -Force -ErrorAction SilentlyContinue

    $directOutput = @()
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $directOutput = & $flang @compileArgs $resolvedSource -o $objectPath 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }

    $mode = 'direct'
    $ok = ($exitCode -eq 0 -and (Test-Path $objectPath))
    $logOutput = @($directOutput)

    if (-not $ok -and $EnableLlvmFallback) {
        $emitOutput = @()
        $ErrorActionPreference = 'Continue'
        try {
            $emitOutput = & $flang @emitLlvmArgs $resolvedSource -o $llvmIrPath 2>&1
            $emitExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorAction
        }

        $logOutput += '=== emit-llvm ==='
        $logOutput += $emitOutput

        if ($emitExitCode -eq 0 -and (Test-Path $llvmIrPath)) {
            $llcOutput = @()
            $ErrorActionPreference = 'Continue'
            try {
                $llcOutput = & $llc "-mtriple=$target" -filetype=obj $llvmIrPath -o $objectPath 2>&1
                $llcExitCode = $LASTEXITCODE
            }
            finally {
                $ErrorActionPreference = $previousErrorAction
            }

            $logOutput += '=== llc ==='
            $logOutput += $llcOutput
            if ($llcExitCode -eq 0 -and (Test-Path $objectPath)) {
                $ok = $true
                $mode = 'llvm+llc'
            }
        }
    }

    $logOutput | Set-Content -Path $logPath -Encoding utf8

    if ($ok) {
        Write-Host "  ok ($mode)"
        & $readObj -h $objectPath | Out-Host
    }
    else {
        Write-Host "  fail"
        Get-Content $logPath | ForEach-Object { Write-Host $_ }
    }

    return [pscustomobject]@{
        Name = $Name
        Success = $ok
        Mode = $mode
        ObjectPath = $objectPath
        LogPath = $logPath
    }
}

$minimalSource = Join-Path $probeRoot 'minimal_probe.f90'
@'
subroutine probe(x)
  real :: x
  x = x + 1.0
end subroutine probe
'@ | Set-Content -Path $minimalSource -Encoding ascii

Push-Location $repoRoot
try {
    $results = @()
    $results += Invoke-FlangProbe -Name 'minimal' -SourcePath $minimalSource
    $results += Invoke-FlangProbe -Name 'pctile' -SourcePath 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib/pctile.f90'
    $results += Invoke-FlangProbe -Name 'baseline' -SourcePath 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib/ft8/baseline.f90'
    $results += Invoke-FlangProbe -Name 'packjt' -SourcePath 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib/packjt.f90'

    Write-Host ''
    Write-Host 'summary:'
    $results | ForEach-Object {
        $status = if ($_.Success) { 'OK' } else { 'FAIL' }
        Write-Host ("  {0,-10} {1,-4} {2}" -f $_.Name, $status, $_.Mode)
    }
}
finally {
    Pop-Location
}
