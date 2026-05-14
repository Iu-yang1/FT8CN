$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')
$probeRoot = Join-Path $repoRoot '.tmp_flang_android_probe'
$modDir = Join-Path $probeRoot 'mod'
$objDir = Join-Path $probeRoot 'obj'
$logDir = Join-Path $probeRoot 'logs'

$flang = 'H:\tools\msys64\ucrt64\bin\flang-new.exe'
$readObj = 'H:\iu_yang1\AndroidSDKLIB\ndk\23.1.7779620\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe'
$target = 'aarch64-linux-android21'

if (-not (Test-Path $flang)) {
    throw "未找到 flang: $flang"
}

if (-not (Test-Path $readObj)) {
    throw "未找到 llvm-readobj: $readObj"
}

New-Item -ItemType Directory -Force -Path $probeRoot, $modDir, $objDir, $logDir | Out-Null

$env:PATH = 'H:\tools\msys64\ucrt64\bin;H:\tools\msys64\usr\bin;' + $env:PATH

$commonArgs = @(
    "--target=$target",
    '-module-dir', $modDir,
    '-I', 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib',
    '-I', 'app/src/main/cpp/wsjtx3/vendor/wsjtx-3.0.0/lib/77bit',
    '-c'
)

function Invoke-FlangProbe {
    param(
        [string] $Name,
        [string] $SourcePath
    )

    $objectPath = Join-Path $objDir ($Name + '.o')
    $logPath = Join-Path $logDir ($Name + '.log')
    $resolvedSource = (Resolve-Path $SourcePath).ProviderPath

    Write-Host "probe => $Name"
    Remove-Item $objectPath -Force -ErrorAction SilentlyContinue

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $flang @commonArgs $resolvedSource -o $objectPath 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $output | Set-Content -Path $logPath -Encoding utf8
    $ok = ($exitCode -eq 0)

    if ($ok -and (Test-Path $objectPath)) {
        Write-Host "  ok"
        & $readObj -h $objectPath | Out-Host
    }
    else {
        Write-Host "  fail"
        Get-Content $logPath | ForEach-Object { Write-Host $_ }
    }

    return [pscustomobject]@{
        Name = $Name
        Success = $ok
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
        Write-Host ("  {0,-10} {1}" -f $_.Name, $status)
    }
}
finally {
    Pop-Location
}
