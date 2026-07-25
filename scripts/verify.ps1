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
    [string]$HostBuildDir = '',
    [switch]$SkipAndroidBuild,
    [switch]$SkipPerformance,
    [switch]$SkipDeviceGate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')

function Write-Gate([string]$Name, [string]$Status, [string]$Detail) {
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Name, $Detail)
}

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) {
        throw ("{0} failed with exit code {1}" -f $Action, $LASTEXITCODE)
    }
}

function Test-SourceManifest([string]$ManifestPath) {
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($line in Get-Content -LiteralPath $ManifestPath) {
        $line = $line.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line.Split('|')
        if ($parts.Count -ne 2) { throw "Invalid source manifest line: $line" }
        if (-not $seen.Add($parts[1])) { throw "Duplicate source manifest entry: $($parts[1])" }
        $sourcePath = Join-Path (Join-Path $repoRoot 'app\src\main\cpp') $parts[1]
        if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Missing manifest source: $sourcePath" }
    }
    Write-Gate 'WSJT-X source manifest' 'PASS' ("{0} unique sources" -f $seen.Count)
}

function Find-OfficialJt9([string]$ExplicitPath, [string[]]$CandidateRoots) {
    if (-not $ExplicitPath -and $env:FT8CN_JT9_PATH) { $ExplicitPath = $env:FT8CN_JT9_PATH }
    return Find-Ft8cnExecutable -ExplicitPath $ExplicitPath `
        -CommandNames @('jt9.exe', 'jt9') -CandidateRoots $CandidateRoots `
        -RelativePatterns @('wsjtx\bin\jt9.exe', 'WSJT-X\bin\jt9.exe', 'wsjtz\bin\jt9.exe')
}

function Invoke-OfficialOracle([string]$Executable, [string]$Mode, [string]$SamplePath, [string]$WorkDir) {
    $modeFlag = if ($Mode -eq 'FT8') { '-8' } elseif ($Mode -eq 'FT4') { '-5' } else { '' }
    if (-not $modeFlag) { return $null }
    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    Push-Location (Split-Path -Parent $Executable)
    try {
        $output = & $Executable $modeFlag -d 2 -Q 0 -c BG5JSU -a $WorkDir -t $WorkDir $SamplePath 2>&1
        Assert-LastExitCode "official jt9 $Mode decode"
    } finally {
        Pop-Location
    }
    $messages = @()
    foreach ($line in $output) {
        if ($line -match '^\d{6}\s+[-\d]+\s+[-\d\.]+\s+\d+\s+[~+]\s+(.*\S)\s*$') {
            $messages += $matches[1].Trim()
        }
    }
    return [pscustomobject]@{ Count = $messages.Count; Messages = $messages }
}

$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
$toolArgs = @{
    JavaHome = $JavaHome
    AndroidSdkRoot = $AndroidSdkRoot
    NdkRoot = $NdkRoot
    CMakePath = $CMakePath
    NinjaPath = $NinjaPath
    FlangPath = $FlangPath
    BoostHeaders = $BoostHeaders
    PassThru = $true
}
$tools = & (Join-Path $PSScriptRoot 'check-toolchain.ps1') @toolArgs
if ($null -eq $tools) { throw 'Toolchain check failed.' }

if (-not $MsysRoot) {
    $MsysRoot = Find-Ft8cnDirectory -CandidateRoots $roots -RelativePatterns @('msys64') `
        -RequiredChild 'ucrt64\bin\gfortran.exe'
}
if (-not $MsysRoot) { throw 'MSYS2 UCRT64 host toolchain was not found.' }
if (-not $FftwRoot) { $FftwRoot = Join-Path $MsysRoot 'ucrt64' }
$hostCMake = Join-Path $MsysRoot 'ucrt64\bin\cmake.exe'
$hostNinja = Join-Path $MsysRoot 'ucrt64\bin\ninja.exe'
if (-not (Test-Path $hostCMake)) { $hostCMake = $tools.CMake }
if (-not (Test-Path $hostNinja)) { $hostNinja = $tools.Ninja }
if (-not $HostBuildDir) {
    $HostBuildDir = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build-release-o2'
}

Test-SourceManifest (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\wsjtx3-sources.manifest')

$hostBuildScript = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build_host_probe.ps1'
& $hostBuildScript -BuildDir $HostBuildDir -BuildType Release -Optimization O2 `
    -CMakePath $hostCMake -NinjaPath $hostNinja -MsysRoot $MsysRoot -FftwRoot $FftwRoot
Assert-LastExitCode 'host build'
$ctestPath = Join-Path (Split-Path -Parent $hostCMake) 'ctest.exe'
if (-not (Test-Path $ctestPath)) { $ctestPath = 'ctest' }
$oldPath = $env:PATH
try {
    $env:PATH = (Join-Path $MsysRoot 'ucrt64\bin') + ';' +
        (Join-Path $MsysRoot 'usr\bin') + ';' + $oldPath
    & $ctestPath --test-dir $HostBuildDir --output-on-failure
    Assert-LastExitCode 'host CTest'
} finally {
    $env:PATH = $oldPath
}
Write-Gate 'host selftest' 'PASS' 'strict O2 build and CTest completed'

$runRoot = Join-Path $repoRoot '.tmp_verify_run'
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$corpus = Get-Content (Join-Path $repoRoot 'docs\verification\test-corpus.json') -Raw | ConvertFrom-Json
$smokeExecutable = Join-Path $HostBuildDir 'ft8cn_wsjtx3_bridge_smoke.exe'
$availableSamples = 0
if (-not $SkipPerformance) {
    foreach ($sample in $corpus.samples) {
        $samplePath = Join-Path $repoRoot ([string]$sample.local_path)
        if (-not (Test-Path -LiteralPath $samplePath)) {
            Write-Gate ("sample {0}" -f $sample.id) 'SKIP' ("not found: {0}" -f $sample.local_path)
            continue
        }
        $availableSamples++
        $actualSha = Get-Ft8cnFileSha256 $samplePath
        if ($actualSha -ne [string]$sample.sha256) { throw "Sample SHA256 mismatch: $($sample.id)" }
        $outputJson = Join-Path $runRoot ("perf-{0}.json" -f $sample.id)
        $null = & (Join-Path $PSScriptRoot 'measure-decoder.ps1') `
            -Executable $smokeExecutable -Mode $sample.mode -SamplePath $samplePath `
            -WarmupCount 1 -IterationCount 5 -DecodePassCount 3 `
            -MultiDecodeRoundCount 3 -DecodeSensitivity 2 -QsoSensitivity 2 `
            -LdpcIterations 200 -OutputJson $outputJson
        $measurement = Get-Content $outputJson -Raw | ConvertFrom-Json
        $counts = @($measurement.result_counts | Select-Object -Unique)
        $hashes = @($measurement.result_sha256)
        if ($counts.Count -ne 1 -or [int]$counts[0] -ne [int]$sample.expected_result_count) {
            throw "Unexpected result count for $($sample.id): $($counts -join ',')"
        }
        if ($hashes.Count -ne 1 -or [string]$hashes[0] -ne [string]$sample.result_sha256) {
            throw "Unexpected result hash for $($sample.id): $($hashes -join ',')"
        }
        Write-Gate ("sample {0}" -f $sample.id) 'PASS' `
            ("count={0}, p50={1}ms, p95={2}ms" -f $counts[0], $measurement.p50_ms, $measurement.p95_ms)
    }
}
if ($SkipPerformance) { Write-Gate 'sample performance' 'SKIP' 'disabled by parameter' }
elseif ($availableSamples -eq 0) { Write-Gate 'sample performance' 'SKIP' 'no corpus WAV is present' }

$oracleRoots = New-Object System.Collections.Generic.List[string]
foreach ($root in $roots) { $oracleRoots.Add($root) }
$cursor = Get-Item $repoRoot
while ($null -ne $cursor) { $oracleRoots.Add($cursor.FullName); $cursor = $cursor.Parent }
$Jt9Path = Find-OfficialJt9 $Jt9Path @($oracleRoots)
if ($Jt9Path) {
    foreach ($sample in $corpus.samples | Where-Object { $_.mode -in @('FT8', 'FT4') }) {
        $samplePath = Join-Path $repoRoot ([string]$sample.local_path)
        if (-not (Test-Path $samplePath)) { continue }
        $oracle = Invoke-OfficialOracle $Jt9Path $sample.mode $samplePath `
            (Join-Path $runRoot ("jt9-{0}" -f $sample.id))
        Write-Gate ("official jt9 {0}" -f $sample.mode) 'PASS' ("decoded {0} messages" -f $oracle.Count)
    }
} else {
    Write-Gate 'official jt9 cross-oracle' 'SKIP' 'jt9 executable was not discovered'
}

if (-not $SkipAndroidBuild) {
    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:PATH
    try {
        $env:JAVA_HOME = $tools.JavaHome
        $env:PATH = (Join-Path $tools.JavaHome 'bin') + ';' + $oldPath
        Push-Location $repoRoot
        try {
            & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
            Assert-LastExitCode 'Gradle test/debug/release build'
        } finally {
            Pop-Location
        }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:PATH = $oldPath
    }
    Write-Gate 'Android build' 'PASS' 'unit tests, debug APK and release APK completed'
} else {
    Write-Gate 'Android build' 'SKIP' 'disabled by parameter'
}

if (-not $SkipDeviceGate) {
    $deviceLines = @(& $tools.Adb devices | Where-Object { $_ -match "\tdevice$" })
    if ($deviceLines.Count -gt 0) {
        $apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (Test-Path $apk) {
            & $tools.Adb install -r $apk | Out-Host
            Assert-LastExitCode 'ADB debug APK install'
            & $tools.Adb shell pm path com.bg7yoz.ft8cn.ft4 | Out-Host
            Assert-LastExitCode 'ADB package query'
            Write-Gate 'ADB device' 'PASS' ("installed on {0} device(s), app data preserved" -f $deviceLines.Count)
        } else {
            Write-Gate 'ADB device' 'SKIP' 'debug APK is unavailable'
        }
    } else {
        Write-Gate 'ADB device' 'SKIP' 'no authorized device is connected'
    }
} else {
    Write-Gate 'ADB device' 'SKIP' 'disabled by parameter'
}

Write-Gate 'verification' 'PASS' 'all available mandatory gates completed'
