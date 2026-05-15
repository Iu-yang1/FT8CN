$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Section {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Title
    )

    Write-Host ''
    Write-Host ("==== {0} ====" -f $Title)
}

function Test-JdkVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaHome,
        [Parameter(Mandatory = $true)]
        [int]$MajorVersion
    )

    $javac = Join-Path $JavaHome 'bin\javac.exe'
    if (-not (Test-Path $javac)) {
        return $false
    }

    $versionText = & $javac -version 2>&1 | Out-String
    return $versionText -match ("javac\s+{0}(\.|$)" -f $MajorVersion)
}

function Find-Jdk17 {
    $candidates = @()

    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }

    $candidates += @(
        'H:\tools\jdks\jdk-17.0.19+10',
        'H:\iu_yang1\jdk17',
        'C:\Users\xiaoy\.jdks\ms-17.0.18'
    )

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if (Test-JdkVersion -JavaHome $candidate -MajorVersion 17) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw 'No usable JDK17 was found.'
}

function Invoke-GradleAssembleDebug {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [Parameter(Mandatory = $true)]
        [string]$JavaHome
    )

    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:Path
    try {
        $env:JAVA_HOME = $JavaHome
        $env:Path = ((Join-Path $JavaHome 'bin') + ';' + $env:Path)
        Push-Location $RepoRoot
        try {
            & .\gradlew.bat :app:assembleDebug
        }
        finally {
            Pop-Location
        }
    }
    finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
    }
}

function Invoke-Jt9Decode {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ModeFlag,
        [Parameter(Mandatory = $true)]
        [string]$SamplePath,
        [Parameter(Mandatory = $true)]
        [string]$WorkDir
    )

    $jt9 = 'H:\iu_yang1\WSJT\wsjtz\bin\jt9.exe'
    if (-not (Test-Path $jt9)) {
        throw ("Official jt9.exe was not found: {0}" -f $jt9)
    }

    if (Test-Path $WorkDir) {
        Remove-Item -Recurse -Force $WorkDir
    }
    New-Item -ItemType Directory -Path $WorkDir | Out-Null

    Push-Location (Split-Path $jt9 -Parent)
    try {
        $output = & .\jt9.exe $ModeFlag -d 2 -Q 0 -c BG5JSU -a $WorkDir -t $WorkDir $SamplePath 2>&1
    }
    finally {
        Pop-Location
    }

    $texts = @()
    foreach ($line in $output) {
        if ($line -match '^\d{6}\s+[-\d]+\s+[-\d\.]+\s+\d+\s+[~+]\s+(.*\S)\s*$') {
            $texts += $matches[1].Trim()
        }
    }

    return @{
        Count = $texts.Count
        Texts = $texts
        Raw = @($output)
    }
}

function Invoke-BridgeSmoke {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [Parameter(Mandatory = $true)]
        [bool]$IsFt8
    )

    $scriptPath = Join-Path $RepoRoot 'app\src\main\cpp\wsjtx3\host\run_bridge_smoke.ps1'
    $oldVars = @{
        FT8CN_SMOKE_RUN_FT8 = $env:FT8CN_SMOKE_RUN_FT8
        FT8CN_SMOKE_RUN_FT4 = $env:FT8CN_SMOKE_RUN_FT4
        FT8CN_SMOKE_PASSES = $env:FT8CN_SMOKE_PASSES
        FT8CN_SMOKE_ROUNDS = $env:FT8CN_SMOKE_ROUNDS
        FT8CN_SMOKE_DECODE_SENSITIVITY = $env:FT8CN_SMOKE_DECODE_SENSITIVITY
        FT8CN_SMOKE_QSO_SENSITIVITY = $env:FT8CN_SMOKE_QSO_SENSITIVITY
        FT8CN_SMOKE_EARLY = $env:FT8CN_SMOKE_EARLY
        FT8CN_SMOKE_WIDEBAND = $env:FT8CN_SMOKE_WIDEBAND
        FT8CN_SMOKE_LDPC = $env:FT8CN_SMOKE_LDPC
        FT8CN_SMOKE_PRINT_COUNT = $env:FT8CN_SMOKE_PRINT_COUNT
    }

    try {
        $env:FT8CN_SMOKE_RUN_FT8 = if ($IsFt8) { '1' } else { '0' }
        $env:FT8CN_SMOKE_RUN_FT4 = if ($IsFt8) { '0' } else { '1' }
        $env:FT8CN_SMOKE_PASSES = '3'
        $env:FT8CN_SMOKE_ROUNDS = '3'
        $env:FT8CN_SMOKE_DECODE_SENSITIVITY = '2'
        $env:FT8CN_SMOKE_QSO_SENSITIVITY = '1'
        $env:FT8CN_SMOKE_EARLY = '1'
        $env:FT8CN_SMOKE_WIDEBAND = '1'
        $env:FT8CN_SMOKE_LDPC = '200'
        $env:FT8CN_SMOKE_PRINT_COUNT = '100'

        Push-Location $RepoRoot
        try {
            $command = 'powershell -ExecutionPolicy Bypass -File "{0}" 2>&1' -f $scriptPath
            $output = & cmd /c $command
        }
        finally {
            Pop-Location
        }
    }
    finally {
        foreach ($entry in $oldVars.GetEnumerator()) {
            if ($null -eq $entry.Value) {
                Remove-Item ("Env:\" + $entry.Key) -ErrorAction SilentlyContinue
            }
            else {
                Set-Item ("Env:\" + $entry.Key) -Value $entry.Value
            }
        }
    }

    $count = 0
    $texts = @()
    foreach ($line in $output) {
        if ($line -match '^\[(FT8|FT4)\]\s+rate=\d+\s+samples=\d+\s+results=(\d+)') {
            $count = [int]$matches[2]
        }
        if ($line -match 'text=(.+)$') {
            $texts += $matches[1].Trim()
        }
    }

    return @{
        Count = $count
        Texts = $texts
        Raw = @($output)
    }
}

function Compare-TextSet {
    param(
        [string[]]$Official,
        [string[]]$Bridge
    )

    $onlyInOfficial = Compare-Object -ReferenceObject $Official -DifferenceObject $Bridge |
        Where-Object { $_.SideIndicator -eq '<=' } |
        ForEach-Object { $_.InputObject }

    $onlyInBridge = Compare-Object -ReferenceObject $Official -DifferenceObject $Bridge |
        Where-Object { $_.SideIndicator -eq '=>' } |
        ForEach-Object { $_.InputObject }

    return @{
        OnlyInOfficial = @($onlyInOfficial)
        OnlyInBridge = @($onlyInBridge)
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$tmpRoot = Join-Path $repoRoot '.tmp_release_check'
$ft8Sample = Join-Path $repoRoot '.tmp_wsjtx\samples\FT8\210703_133430.wav'
$ft4Sample = Join-Path $repoRoot '.tmp_wsjtx\samples\FT4\000000_000002.wav'

if (-not (Test-Path $tmpRoot)) {
    New-Item -ItemType Directory -Path $tmpRoot | Out-Null
}

$releaseReady = $true

Write-Section 'JDK17'
$jdk17 = Find-Jdk17
Write-Host ("Using JDK17: {0}" -f $jdk17)

Write-Section 'Android Build'
try {
    Invoke-GradleAssembleDebug -RepoRoot $repoRoot -JavaHome $jdk17
    Write-Host 'assembleDebug: PASS'
}
catch {
    $releaseReady = $false
    Write-Host ("assembleDebug: FAIL - {0}" -f $_.Exception.Message)
}

Write-Section 'FT8 Baseline'
$ft8Official = Invoke-Jt9Decode -ModeFlag '-8' -SamplePath $ft8Sample -WorkDir (Join-Path $tmpRoot 'jt9_ft8')
$ft8Bridge = Invoke-BridgeSmoke -RepoRoot $repoRoot -IsFt8 $true
$ft8Diff = Compare-TextSet -Official $ft8Official.Texts -Bridge $ft8Bridge.Texts
Write-Host ("Official jt9: {0}" -f $ft8Official.Count)
Write-Host ("Current bridge: {0}" -f $ft8Bridge.Count)
if ($ft8Official.Count -ne $ft8Bridge.Count -or $ft8Diff.OnlyInOfficial.Count -gt 0 -or $ft8Diff.OnlyInBridge.Count -gt 0) {
    $releaseReady = $false
    Write-Host 'FT8 baseline: FAIL'
    if ($ft8Diff.OnlyInOfficial.Count -gt 0) {
        Write-Host ('Only in official: ' + ($ft8Diff.OnlyInOfficial -join ' | '))
    }
    if ($ft8Diff.OnlyInBridge.Count -gt 0) {
        Write-Host ('Only in bridge: ' + ($ft8Diff.OnlyInBridge -join ' | '))
    }
}
else {
    Write-Host 'FT8 baseline: PASS'
}

Write-Section 'FT4 Baseline'
$ft4Official = Invoke-Jt9Decode -ModeFlag '-5' -SamplePath $ft4Sample -WorkDir (Join-Path $tmpRoot 'jt9_ft4')
$ft4Bridge = Invoke-BridgeSmoke -RepoRoot $repoRoot -IsFt8 $false
$ft4Diff = Compare-TextSet -Official $ft4Official.Texts -Bridge $ft4Bridge.Texts
Write-Host ("Official jt9: {0}" -f $ft4Official.Count)
Write-Host ("Current bridge: {0}" -f $ft4Bridge.Count)
if ($ft4Official.Count -ne $ft4Bridge.Count -or $ft4Diff.OnlyInOfficial.Count -gt 0 -or $ft4Diff.OnlyInBridge.Count -gt 0) {
    $releaseReady = $false
    Write-Host 'FT4 baseline: HOLD'
    if ($ft4Diff.OnlyInOfficial.Count -gt 0) {
        Write-Host ('Only in official: ' + ($ft4Diff.OnlyInOfficial -join ' | '))
    }
    if ($ft4Diff.OnlyInBridge.Count -gt 0) {
        Write-Host ('Only in bridge: ' + ($ft4Diff.OnlyInBridge -join ' | '))
    }
}
else {
    Write-Host 'FT4 baseline: PASS'
}

Write-Section 'Summary'
if ($releaseReady) {
    Write-Host 'Release readiness: PASS'
    exit 0
}

Write-Host 'Release readiness: HOLD'
exit 1
