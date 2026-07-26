param(
    [string]$AdbPath = '',
    [string]$JavaHome = '',
    [string]$CorpusManifest = '',
    [string]$OutputJson = '',
    [ValidateRange(1, 5)][int]$WarmupCount = 1,
    [ValidateRange(10, 50)][int]$IterationCount = 10,
    [ValidateSet('ALL', 'FT8', 'FT4', 'Q65')][string]$CaseFilter = 'ALL',
    [ValidateSet('ALL', 'DEBUG', 'RELEASE')][string]$VariantFilter = 'ALL'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE" }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = @(& $AdbPath @Arguments 2>&1)
    Assert-LastExitCode ("adb " + ($Arguments -join ' '))
    return $output
}

if (-not $CorpusManifest) {
    $CorpusManifest = Join-Path $repoRoot 'docs\verification\test-corpus.json'
}
if (-not $OutputJson) {
    $OutputJson = Join-Path $repoRoot '.tmp_verify_run\device-benchmark.json'
}
$CorpusManifest = (Resolve-Path $CorpusManifest).ProviderPath
$corpus = Get-Content -LiteralPath $CorpusManifest -Raw | ConvertFrom-Json

$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
if (-not $AdbPath) {
    $AdbPath = Find-Ft8cnExecutable -ExplicitPath '' -CandidateRoots $roots `
        -CommandNames @('adb.exe', 'adb') `
        -RelativePatterns @('platform-tools\adb.exe', 'AndroidSDKLIB\platform-tools\adb.exe')
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) { throw 'ADB was not found.' }
if (-not $JavaHome) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $JavaCompiler = Find-Ft8cnExecutable -ExplicitPath '' -CandidateRoots $roots `
            -CommandNames @('javac.exe', 'javac') `
            -RelativePatterns @('jdks\jdk*\bin\javac.exe', 'jdk*\bin\javac.exe', 'bin\javac.exe')
        if ($JavaCompiler) { $JavaHome = Split-Path -Parent (Split-Path -Parent $JavaCompiler) }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK was not found.'
}

$devices = @(Invoke-Adb devices | Where-Object { $_ -match "\tdevice$" })
if ($devices.Count -eq 0) { throw 'BLOCKED_DEVICE: no authorized ADB device is connected.' }
if ($devices.Count -gt 1) { throw 'More than one ADB device is connected; set ANDROID_SERIAL.' }

$modeSamples = @{}
foreach ($sample in $corpus.samples) { $modeSamples[[string]$sample.mode] = $sample }
foreach ($mode in @('FT8', 'FT4', 'Q65')) {
    if (-not $modeSamples.ContainsKey($mode)) { throw "Corpus manifest has no $mode sample." }
    $path = Join-Path $repoRoot ([string]$modeSamples[$mode].local_path)
    if (-not (Test-Path -LiteralPath $path)) { throw "Corpus sample is missing: $path" }
    if ((Get-Ft8cnFileSha256 $path) -ne [string]$modeSamples[$mode].sha256) {
        throw "Corpus SHA256 mismatch: $mode"
    }
}

$assetDirectory = Join-Path $repoRoot 'app\src\androidTest\assets\corpus'
$assetMap = @{
    FT8 = Join-Path $assetDirectory 'ft8.wav'
    FT4 = Join-Path $assetDirectory 'ft4.wav'
    Q65 = Join-Path $assetDirectory 'q65.wav'
}
$createdAssets = New-Object System.Collections.Generic.List[string]

function Install-Apk([string]$Path, [switch]$AllowDowngrade) {
    $arguments = @('install', '-r', '-t')
    if ($AllowDowngrade) { $arguments += '-d' }
    $arguments += $Path
    $output = Invoke-Adb @arguments
    if (($output -join "`n") -notmatch 'Success') { throw "APK install did not report success: $output" }
}

function Invoke-BenchmarkVariant([string]$Variant) {
    $component = 'com.bg7yoz.ft8cn.ft4.test/com.bg7yoz.ft8cn.diagnostics.FtxDeviceBenchmarkInstrumentation'
    $arguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'build_variant', $Variant,
        '-e', 'warmups', [string]$WarmupCount,
        '-e', 'iterations', [string]$IterationCount,
        '-e', 'case_filter', $CaseFilter,
        '-e', 'ft8_expected', [string]$modeSamples.FT8.expected_result_count,
        '-e', 'ft4_expected', [string]$modeSamples.FT4.expected_result_count,
        '-e', 'q65_expected', [string]$modeSamples.Q65.expected_result_count,
        $component
    )
    $stdoutPath = Join-Path $repoRoot ".tmp_verify_run\instrumentation-$Variant.stdout.txt"
    $stderrPath = Join-Path $repoRoot ".tmp_verify_run\instrumentation-$Variant.stderr.txt"
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

    $process = $null
    $taskLocked = $false
    try {
        $process = Start-Process -FilePath $AdbPath -ArgumentList $arguments `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
            -WindowStyle Hidden -PassThru

        $activityDeadline = [DateTime]::UtcNow.AddSeconds(30)
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $activityDeadline) {
            $activityDump = (Invoke-Adb shell dumpsys activity activities) -join "`n"
            $taskMatch = [regex]::Match(
                $activityDump,
                'com\.bg7yoz\.ft8cn\.ft4/com\.bg7yoz\.ft8cn\.diagnostics\.DeviceBenchmarkActivity t(\d+)'
            )
            if ($taskMatch.Success) {
                $taskId = $taskMatch.Groups[1].Value
                $lockOutput = (Invoke-Adb shell am task lock $taskId) -join "`n"
                if ($lockOutput -notmatch 'lockTaskMode') {
                    throw "Unable to lock benchmark task $taskId`: $lockOutput"
                }
                $taskLocked = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $taskLocked) {
            throw "Device benchmark $Variant did not expose its internal foreground activity."
        }
        if (-not $process.WaitForExit(60 * 60 * 1000)) {
            throw "Device benchmark $Variant exceeded the 60 minute device timeout."
        }
    } finally {
        if ($taskLocked) {
            $null = Invoke-Adb shell am task lock stop
        }
        if ($process -and -not $process.HasExited) {
            $null = Invoke-Adb shell am force-stop com.bg7yoz.ft8cn.ft4
            if (-not $process.WaitForExit(10000)) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }

    $output = @()
    if (Test-Path -LiteralPath $stdoutPath) { $output += Get-Content -LiteralPath $stdoutPath }
    if (Test-Path -LiteralPath $stderrPath) { $output += Get-Content -LiteralPath $stderrPath }
    $processExitCode = $null
    try {
        $process.Refresh()
        $processExitCode = $process.ExitCode
    } catch { }
    if ($null -ne $processExitCode -and $processExitCode -ne 0) {
        throw "adb instrumentation for $Variant failed with exit code ${processExitCode}:`n$($output -join "`n")"
    }
    $text = $output -join "`n"
    Set-Content -LiteralPath (Join-Path $repoRoot ".tmp_verify_run\instrumentation-$Variant.txt") `
        -Value $text -Encoding UTF8
    if ($text -match 'FT8CN_DEVICE_BENCHMARK=FAIL' -or $text -match 'INSTRUMENTATION_FAILED') {
        throw "Device benchmark $Variant failed:`n$text"
    }

    $pathMatch = [regex]::Match($text, '(?m)^INSTRUMENTATION_RESULT: report_path=(.+?)\s*$')
    if ($pathMatch.Success) {
        $remotePath = $pathMatch.Groups[1].Value.Trim()
        $localPath = Join-Path $repoRoot ".tmp_verify_run\device-$Variant.json"
        $null = Invoke-Adb pull $remotePath $localPath
        if (Test-Path -LiteralPath $localPath) {
            return Get-Content -LiteralPath $localPath -Raw | ConvertFrom-Json
        }
    }

    $base64Match = [regex]::Match($text, '(?m)^INSTRUMENTATION_RESULT: report_base64=(\S+)\s*$')
    if (-not $base64Match.Success) { throw "Device benchmark $Variant returned no JSON report." }
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($base64Match.Groups[1].Value))
    return $json | ConvertFrom-Json
}

try {
    New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null
    foreach ($mode in @('FT8', 'FT4', 'Q65')) {
        $destination = $assetMap[$mode]
        if (Test-Path -LiteralPath $destination) {
            throw "Refusing to overwrite pre-existing Android test asset: $destination"
        }
        Copy-Item -LiteralPath (Join-Path $repoRoot ([string]$modeSamples[$mode].local_path)) `
            -Destination $destination
        $createdAssets.Add($destination)
    }

    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:PATH
    try {
        $env:JAVA_HOME = $JavaHome
        $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $oldPath
        Push-Location $repoRoot
        try {
            $gradleTasks = @(':app:assembleDebugAndroidTest')
            if ($VariantFilter -in @('ALL', 'DEBUG')) { $gradleTasks += ':app:assembleDebug' }
            if ($VariantFilter -in @('ALL', 'RELEASE')) { $gradleTasks += ':app:assembleRelease' }
            & .\gradlew.bat @gradleTasks
            Assert-LastExitCode 'Android app/test APK build'
        } finally {
            Pop-Location
        }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:PATH = $oldPath
    }

    $debugApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $releaseApk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
    $testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    $requiredApks = @($testApk)
    if ($VariantFilter -in @('ALL', 'DEBUG')) { $requiredApks += $debugApk }
    if ($VariantFilter -in @('ALL', 'RELEASE')) { $requiredApks += $releaseApk }
    foreach ($apk in $requiredApks) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Built APK is missing: $apk" }
    }

    $debugReport = $null
    $releaseReport = $null
    if ($VariantFilter -in @('ALL', 'DEBUG')) {
        Install-Apk $debugApk
        Install-Apk $testApk
        $debugReport = Invoke-BenchmarkVariant 'debug'
    }

    if ($VariantFilter -in @('ALL', 'RELEASE')) {
        Install-Apk $releaseApk -AllowDowngrade
        Install-Apk $testApk
        $releaseReport = Invoke-BenchmarkVariant 'release'
    }

    $mismatches = New-Object System.Collections.ArrayList
    if ($null -ne $debugReport -and $null -ne $releaseReport) {
        $debugCases = @{}
        foreach ($item in $debugReport.cases) { $debugCases["$($item.name)|$($item.source_sample_rate)"] = $item }
        foreach ($item in $releaseReport.cases) {
            $key = "$($item.name)|$($item.source_sample_rate)"
            if (-not $debugCases.ContainsKey($key)) {
                $null = $mismatches.Add("release-only case: $key")
                continue
            }
            $debugItem = $debugCases[$key]
            if ([int]$debugItem.result_count -ne [int]$item.result_count) {
                $null = $mismatches.Add("count mismatch $key")
            }
            if ([string]$debugItem.result_sha256 -ne [string]$item.result_sha256) {
                $null = $mismatches.Add("result SHA256 mismatch $key")
            }
            $debugCases.Remove($key)
        }
        foreach ($key in $debugCases.Keys) { $null = $mismatches.Add("debug-only case: $key") }
    }
    if ($mismatches.Count -gt 0) { throw ($mismatches -join '; ') }

    $result = [ordered]@{
        schema_version = 1
        passed = $true
        generated_at_utc = [DateTime]::UtcNow.ToString('o')
        device_serial = (($devices[0] -split "\s+")[0])
        variant_filter = $VariantFilter
        debug = $debugReport
        release = $releaseReport
        debug_release_mismatches = @()
    }
    $outputDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputJson))
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $result | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $OutputJson -Encoding UTF8
    Write-Host "Android device benchmark: PASS"
} finally {
    foreach ($asset in $createdAssets) {
        if (Test-Path -LiteralPath $asset) { Remove-Item -LiteralPath $asset -Force }
    }
}
