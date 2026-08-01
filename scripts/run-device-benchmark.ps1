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
. (Join-Path $PSScriptRoot 'verification-common.ps1')

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE" }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    # adb writes successful transfer progress to stderr. PowerShell 5.1 turns that
    # stream into ErrorRecord objects under Stop, so trust the native exit code.
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $AdbPath @Arguments 2>&1 | ForEach-Object { "$_" })
        $adbExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($adbExitCode -ne 0) {
        throw "adb $($Arguments -join ' ') failed with exit code $adbExitCode`n$($output -join "`n")"
    }
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
    $component = 'com.bg7yoz.ft8cn.ft4.test/androidx.test.runner.AndroidJUnitRunner'
    $arguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'com.bg7yoz.ft8cn.diagnostics.FtxDeviceBenchmarkInstrumentation',
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
    if ($text -match 'FT8CN_DEVICE_BENCHMARK=FAIL' -or
            $text -match 'INSTRUMENTATION_FAILED' -or
            $text -match 'FAILURES!!!') {
        throw "Device benchmark $Variant failed:`n$text"
    }
    # AndroidJUnitRunner uses INSTRUMENTATION_CODE: -1 for a successful run.
    # Require both the app gate marker and the JUnit summary instead.
    if ($text -notmatch 'FT8CN_DEVICE_BENCHMARK=PASS' -or
            $text -notmatch '(?m)^OK \(1 test\)\s*$') {
        throw "Device benchmark $Variant did not report a complete success:`n$text"
    }

    $pathMatch = [regex]::Match(
        $text,
        '(?m)^INSTRUMENTATION_(?:STATUS|RESULT): report_path=(.+?)\s*$'
    )
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

function Invoke-Q65StreamingGate([string]$Variant) {
    $component = 'com.bg7yoz.ft8cn.ft4.test/androidx.test.runner.AndroidJUnitRunner'
    $classes = @(
        'com.bg7yoz.ft8cn.wave.FtxStreamingResamplerInstrumentationTest',
        'com.bg7yoz.ft8cn.ft8transmit.Q65WaveStreamInstrumentationTest'
    ) -join ','
    $clearLogcatArguments = @('logcat', '-c')
    $null = Invoke-Adb @clearLogcatArguments
    # Explicit strings keep PowerShell 5.1 from treating adb's -w/-r as common parameters.
    $adbArguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $classes, $component)
    $output = @(Invoke-Adb @adbArguments)
    $text = $output -join "`n"
    if ($text -match 'INSTRUMENTATION_FAILED|FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2') {
        throw "Q65 streaming gate $Variant failed:`n$text"
    }
    $summary = [regex]::Match($text, '(?m)^OK \((\d+) tests?\)\s*$')
    if (-not $summary.Success -or [int]$summary.Groups[1].Value -lt 7) {
        throw "Q65 streaming gate $Variant did not report all tests:`n$text"
    }
    $memoryLines = New-Object System.Collections.Generic.List[string]
    foreach ($statusKey in @('ft8cn_q65_rx_evidence', 'ft8cn_q65_tx_evidence')) {
        $match = [regex]::Match(
            $text,
            "(?m)^INSTRUMENTATION_STATUS: $statusKey=(.+?)\s*$"
        )
        if ($match.Success) { $memoryLines.Add($match.Groups[1].Value.Trim()) }
    }
    if ($memoryLines.Count -lt 2) {
        # 旧 test APK 只写 Logcat；保留短暂回退，新的 APK 使用可靠的 status Bundle。
        $logcatArguments = @('logcat', '-d', '-s', 'Q65StreamMemoryTest:I', '*:S')
        $memoryDeadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            foreach ($line in @(Invoke-Adb @logcatArguments |
                    Where-Object { $_ -match 'Q65 (RX|TX) 300s' })) {
                if (-not $memoryLines.Contains([string]$line)) { $memoryLines.Add([string]$line) }
            }
            if ($memoryLines.Count -ge 2) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $memoryDeadline)
    }
    if ($memoryLines.Count -lt 2) {
        throw "Q65 streaming gate $Variant returned no bounded-memory evidence."
    }
    $rxEvidence = @($memoryLines.ToArray() | Where-Object { $_ -match 'Q65 RX 300s' })
    $txEvidence = @($memoryLines.ToArray() | Where-Object { $_ -match 'Q65 TX 300s' })
    if (
        ($rxEvidence.Count -ne 1) -or
        ($rxEvidence[0] -notmatch 'sourceChunk=4096') -or
        ($rxEvidence[0] -notmatch 'outputSamples=3600000') -or
        ($rxEvidence[0] -notmatch 'finalJavaArraySamples=0')
    ) {
        throw "Q65 RX evidence does not prove native-owned bounded streaming: $($rxEvidence -join '; ')"
    }
    if ($txEvidence.Count -ne 1 -or $txEvidence[0] -notmatch 'chunkSamples=4096') {
        throw "Q65 TX evidence does not prove bounded streaming: $($txEvidence -join '; ')"
    }
    return [pscustomobject]@{
        passed = $true
        variant = $Variant
        test_count = [int]$summary.Groups[1].Value
        source_chunk_samples = 4096
        tx_chunk_samples = 4096
        rx_final_12k_samples = 3600000
        memory_evidence = $memoryLines.ToArray()
    }
}

try {
    New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null
    foreach ($mode in @('FT8', 'FT4', 'Q65')) {
        $destination = $assetMap[$mode]
        $source = Join-Path $repoRoot ([string]$modeSamples[$mode].local_path)
        if (Test-Path -LiteralPath $destination) {
            if ((Get-Ft8cnFileSha256 $destination) -ne (Get-Ft8cnFileSha256 $source)) {
                throw "Refusing to overwrite a different Android test asset: $destination"
            }
            Write-Host "Reusing verified Android test asset: $destination"
            continue
        }
        Copy-Item -LiteralPath $source -Destination $destination
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

    $debugApk = $null
    $releaseApk = $null
    if ($VariantFilter -in @('ALL', 'DEBUG')) {
        $debugApk = Resolve-GradleApkOutput `
            (Join-Path $repoRoot 'app\build\outputs\apk\debug')
    }
    if ($VariantFilter -in @('ALL', 'RELEASE')) {
        $releaseApk = Resolve-GradleApkOutput `
            (Join-Path $repoRoot 'app\build\outputs\apk\release')
    }
    $testApk = Resolve-GradleApkOutput `
        (Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug')
    $requiredApks = @($testApk)
    if ($VariantFilter -in @('ALL', 'DEBUG')) { $requiredApks += $debugApk }
    if ($VariantFilter -in @('ALL', 'RELEASE')) { $requiredApks += $releaseApk }
    foreach ($apk in $requiredApks) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Built APK is missing: $apk" }
    }
    if ($VariantFilter -in @('ALL', 'RELEASE')) {
        $releaseSignature = Get-AndroidApkSignatureStatus -ApkPath $releaseApk `
            -AndroidSdkRoot $env:ANDROID_SDK_ROOT
        if (-not $releaseSignature.checked) {
            throw "BLOCKED_RELEASE_SIGNING: $($releaseSignature.detail)"
        }
        if (-not $releaseSignature.signed) {
            throw 'BLOCKED_RELEASE_SIGNING: Release APK is unsigned and cannot be installed.'
        }
        if ($releaseSignature.debug_certificate) {
            throw 'BLOCKED_RELEASE_SIGNING: Android debug certificates are not accepted for Release qualification.'
        }
    }

    $debugReport = $null
    $releaseReport = $null
    $debugStreamingReport = $null
    $releaseStreamingReport = $null
    if ($VariantFilter -in @('ALL', 'DEBUG')) {
        Install-Apk $debugApk
        Install-Apk $testApk
        $debugStreamingReport = Invoke-Q65StreamingGate 'debug'
        $debugReport = Invoke-BenchmarkVariant 'debug'
    }

    if ($VariantFilter -in @('ALL', 'RELEASE')) {
        Install-Apk $releaseApk -AllowDowngrade
        Install-Apk $testApk
        $releaseStreamingReport = Invoke-Q65StreamingGate 'release'
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
        device_serial = '[redacted]'
        variant_filter = $VariantFilter
        debug = $debugReport
        release = $releaseReport
        q65_streaming = [ordered]@{
            passed = (($null -eq $debugStreamingReport -or $debugStreamingReport.passed) -and
                ($null -eq $releaseStreamingReport -or $releaseStreamingReport.passed))
            debug = $debugStreamingReport
            release = $releaseStreamingReport
        }
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
