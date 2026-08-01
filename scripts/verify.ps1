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
    [string]$ReportPath = '',
    [string]$PerformanceBaselinePath = '',
    [switch]$SkipAndroidBuild,
    [switch]$SkipPerformance,
    [switch]$SkipDeviceGate,
    [switch]$SkipSanitizers
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')
. (Join-Path $PSScriptRoot 'verification-common.ps1')

$runRoot = Join-Path $repoRoot '.tmp_verify_run'
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
if (-not $ReportPath) { $ReportPath = Join-Path $runRoot 'verification-report.json' }
$gateRoot = Join-Path $runRoot 'gates'
New-Item -ItemType Directory -Force -Path $gateRoot | Out-Null

$gateResults = New-Object System.Collections.ArrayList
$sampleResults = New-Object System.Collections.ArrayList
$oracleResults = New-Object System.Collections.ArrayList
$hasFailure = $false
$hostPassed = $false
$oraclePassed = $false
$androidPassed = $false
$devicePassed = $false
$sanitizerPassed = $false
$oracleBlocked = $false
$deviceBlocked = $false
$sanitizerBlocked = $false
$corpusBlocked = $false
$q65StreamingBlocked = $true
$q65StreamingPassed = $false
$tools = $null
$oracleFrequencyToleranceHz = 3.2
$oracleDtToleranceSec = 0.06
$decodeMinimumHz = 0.0
$decodeMaximumHz = 3000.0

function Add-Gate {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Detail,
        [AllowNull()][object]$Data = $null
    )

    $item = [pscustomobject]@{
        name = $Name
        status = $Status
        detail = $Detail
        timestamp_utc = [DateTime]::UtcNow.ToString('o')
        data = $Data
    }
    $null = $gateResults.Add($item)
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Name, $Detail)
}

function Assert-LastExitCode([string]$Action) {
    # Windows PowerShell does not always create LASTEXITCODE for a successful child script.
    $exitCodeVariable = $null
    $exitCodeVariable = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -ne $exitCodeVariable -and [int]$exitCodeVariable.Value -ne 0) {
        throw ("{0} failed with exit code {1}" -f $Action, $exitCodeVariable.Value)
    }
}

function Get-SafeFileName([string]$Value) {
    return [regex]::Replace($Value.ToLowerInvariant(), '[^a-z0-9._-]+', '-')
}

function Write-VerificationReport {
    param([string[]]$FinalStates)

    $head = (& git -C $repoRoot rev-parse HEAD).Trim()
    $branch = (& git -C $repoRoot branch --show-current).Trim()
    $status = @(& git -C $repoRoot status --short)
    $report = [ordered]@{
        schema_version = 2
        generated_at_utc = [DateTime]::UtcNow.ToString('o')
        repository = [ordered]@{
            root = $repoRoot
            branch = $branch
            head = $head
            worktree_status = $status
        }
        toolchain = $tools
        gates = @($gateResults.ToArray())
        samples = @($sampleResults.ToArray())
        oracle = @($oracleResults.ToArray())
        final_states = $FinalStates
    }
    $reportJson = $report | ConvertTo-Json -Depth 16
    $reportDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($ReportPath))
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    Set-Content -LiteralPath $ReportPath -Value $reportJson -Encoding UTF8

    for ($index = 0; $index -lt $gateResults.Count; $index++) {
        $gate = $gateResults[$index]
        $name = Get-SafeFileName ([string]$gate.name)
        $path = Join-Path $gateRoot ('{0:D2}-{1}.json' -f $index, $name)
        $gate | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding UTF8
    }
    Write-Host ("Verification JSON: {0}" -f $ReportPath)
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
    return $seen.Count
}

function Find-OfficialJt9 {
    param([string]$ExplicitPath, [string[]]$CandidateRoots)

    foreach ($candidate in @($ExplicitPath, $env:FT8CN_JT9_PATH)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path $candidate).ProviderPath
        }
    }
    foreach ($name in @('jt9.exe', 'jt9')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command -and (Test-Path -LiteralPath $command.Source)) { return $command.Source }
    }

    $patterns = @(
        'jt9.exe',
        'bin\jt9.exe',
        'wsjtx\bin\jt9.exe',
        'WSJT-X\bin\jt9.exe',
        'wsjtx-*\bin\jt9.exe',
        'WSJT-X-*\bin\jt9.exe',
        'wsjtx*\bin\jt9.exe',
        '*WSJT*\bin\jt9.exe'
    )
    $seenRoots = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $CandidateRoots) {
        if (-not $root -or -not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        $resolvedRoot = (Resolve-Path $root).ProviderPath
        if (-not $seenRoots.Add($resolvedRoot)) { continue }
        foreach ($pattern in $patterns) {
            $match = Get-ChildItem -Path (Join-Path $resolvedRoot $pattern) -File -ErrorAction SilentlyContinue |
                Sort-Object FullName | Select-Object -First 1
            if ($null -ne $match) { return $match.FullName }
        }
        foreach ($wsjtDirectory in @(Get-ChildItem -LiteralPath $resolvedRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(?i)wsjt' })) {
            $match = Get-ChildItem -LiteralPath $wsjtDirectory.FullName -Filter jt9.exe -File -Recurse `
                -Depth 4 -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -First 1
            if ($null -ne $match) { return $match.FullName }
        }
    }
    return $null
}

function Invoke-OfficialOracle {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][ValidateSet('FT8', 'FT4')][string]$Mode,
        [Parameter(Mandatory = $true)][string]$SamplePath,
        [Parameter(Mandatory = $true)][string]$WorkDir
    )

    $modeFlag = if ($Mode -eq 'FT8') { '-8' } else { '-5' }
    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    $oldPath = $env:PATH
    $oldLocation = Get-Location
    try {
        $env:PATH = (Split-Path -Parent $Executable) + ';' + $oldPath
        Set-Location $WorkDir
        $output = @(& $Executable $modeFlag -d 2 -Q 0 -c BG5JSU -a $WorkDir -t $WorkDir $SamplePath 2>&1)
        Assert-LastExitCode "official jt9 $Mode decode"
    } finally {
        Set-Location $oldLocation
        $env:PATH = $oldPath
    }
    $stdoutText = $output -join "`n"
    Set-Content -LiteralPath (Join-Path $WorkDir 'jt9-stdout.txt') -Value $stdoutText -Encoding UTF8
    $records = @(ConvertFrom-OfficialJt9Output $stdoutText -Mode $Mode)
    $decodedPath = Join-Path $WorkDir 'decoded.txt'
    if ($records.Count -eq 0 -and (Test-Path -LiteralPath $decodedPath)) {
        $records = @(ConvertFrom-OfficialJt9Output (Get-Content $decodedPath -Raw) -Mode $Mode)
    }
    return [pscustomobject]@{
        records = $records
        stdout_path = Join-Path $WorkDir 'jt9-stdout.txt'
        decoded_path = if (Test-Path -LiteralPath $decodedPath) { $decodedPath } else { '' }
    }
}

function Write-OracleDifferences([object]$Comparison) {
    if ($Comparison.count_mismatch) {
        Write-Host ("count mismatch: official={0}, FT8CN={1}" -f `
            $Comparison.official_count, $Comparison.ft8cn_count)
    }
    foreach ($record in $Comparison.only_in_official) {
        Write-Host ("only in official: {0}" -f (Format-FtxRecord $record))
    }
    foreach ($record in $Comparison.only_in_ft8cn) {
        Write-Host ("only in FT8CN: {0}" -f (Format-FtxRecord $record))
    }
    foreach ($mismatch in $Comparison.metric_mismatches) {
        Write-Host ("frequency/DT mismatch: {0} official={1:F3}Hz/{2:F3}s FT8CN={3:F3}Hz/{4:F3}s" -f `
            $mismatch.message, $mismatch.official_frequency_hz, $mismatch.official_dt_sec, `
            $mismatch.ft8cn_frequency_hz, $mismatch.ft8cn_dt_sec)
    }
}

$startedAt = [DateTime]::UtcNow
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
try {
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
    if ($null -eq $tools) { throw 'Toolchain check returned no result.' }
    Add-Gate 'toolchain' 'PASS' 'required local tools discovered' $tools
} catch {
    $hasFailure = $true
    Add-Gate 'toolchain' 'FAIL' $_.Exception.Message
}

if (-not $MsysRoot) {
    $MsysRoot = Find-Ft8cnDirectory -CandidateRoots $roots -RelativePatterns @('msys64') `
        -RequiredChild 'ucrt64\bin\gfortran.exe'
}
if (-not $FftwRoot -and $MsysRoot) { $FftwRoot = Join-Path $MsysRoot 'ucrt64' }
if (-not $HostBuildDir) {
    $HostBuildDir = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build-release-o2'
}

$hostInfrastructurePassed = $false
if ($null -ne $tools -and $MsysRoot) {
    try {
        $manifestCount = Test-SourceManifest `
            (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\wsjtx3-sources.manifest')
        Add-Gate 'WSJT-X source manifest' 'PASS' ("{0} unique sources" -f $manifestCount)

        & (Join-Path $PSScriptRoot 'tests\verification-common.tests.ps1')
        Assert-LastExitCode 'verification parser tests'
        Add-Gate 'verification parser tests' 'PASS' 'parsing, stable ordering and multiset differences verified'

        $hostCMake = Join-Path $MsysRoot 'ucrt64\bin\cmake.exe'
        $hostNinja = Join-Path $MsysRoot 'ucrt64\bin\ninja.exe'
        if (-not (Test-Path $hostCMake)) { $hostCMake = $tools.CMake }
        if (-not (Test-Path $hostNinja)) { $hostNinja = $tools.Ninja }
        $hostBuildScript = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build_host_probe.ps1'
        & $hostBuildScript -BuildDir $HostBuildDir -BuildType Release -Optimization O2 `
            -CMakePath $hostCMake -NinjaPath $hostNinja -MsysRoot $MsysRoot -FftwRoot $FftwRoot
        Assert-LastExitCode 'strict O2 host build'
        $ctestPath = Join-Path (Split-Path -Parent $hostCMake) 'ctest.exe'
        if (-not (Test-Path $ctestPath)) { $ctestPath = 'ctest' }
        $oldPath = $env:PATH
        try {
            $env:PATH = (Join-Path $MsysRoot 'ucrt64\bin') + ';' + `
                (Join-Path $MsysRoot 'usr\bin') + ';' + $oldPath
            & $ctestPath --test-dir $HostBuildDir --output-on-failure
            Assert-LastExitCode 'host CTest'
        } finally {
            $env:PATH = $oldPath
        }
        $hostInfrastructurePassed = $true
        Add-Gate 'host O2 CTest' 'PASS' 'Release -O2 -DNDEBUG build and CTest completed'
    } catch {
        $hasFailure = $true
        Add-Gate 'host O2 CTest' 'FAIL' $_.Exception.Message
    }
} else {
    $hasFailure = $true
    Add-Gate 'host O2 CTest' 'FAIL' 'MSYS2/toolchain discovery failed'
}

$corpusPath = Join-Path $repoRoot 'docs\verification\test-corpus.json'
$corpus = $null
$sampleMeasurements = @{}
$allSamplesPassed = $hostInfrastructurePassed
if ($SkipPerformance) {
    $corpusBlocked = $true
    $allSamplesPassed = $false
    Add-Gate 'sample corpus regression' 'BLOCKED_CORPUS' 'disabled by -SkipPerformance; HOST_RC_PASS is not allowed'
} elseif (-not (Test-Path -LiteralPath $corpusPath)) {
    $corpusBlocked = $true
    $allSamplesPassed = $false
    Add-Gate 'sample corpus regression' 'BLOCKED_CORPUS' 'test-corpus.json is missing'
} elseif ($hostInfrastructurePassed) {
    $corpus = Get-Content -LiteralPath $corpusPath -Raw | ConvertFrom-Json
    if ($corpus.PSObject.Properties.Name -contains 'oracle_policy') {
        $oracleFrequencyToleranceHz = [double]$corpus.oracle_policy.frequency_tolerance_hz
        $oracleDtToleranceSec = [double]$corpus.oracle_policy.dt_tolerance_sec
        $decodeMinimumHz = [double]$corpus.oracle_policy.minimum_frequency_hz
        $decodeMaximumHz = [double]$corpus.oracle_policy.maximum_frequency_hz
    }
    $smokeExecutable = Join-Path $HostBuildDir 'ft8cn_wsjtx3_bridge_smoke.exe'
    $referenceP95 = @{ FT8 = 543.117; FT4 = 269.472; Q65 = 268.751 }
    $referenceSource = @{ FT8 = 'historical'; FT4 = 'historical'; Q65 = 'historical' }
    if ($PerformanceBaselinePath) {
        $resolvedBaselinePath = (Resolve-Path -LiteralPath $PerformanceBaselinePath).ProviderPath
        $performanceBaseline = Get-Content -LiteralPath $resolvedBaselinePath -Raw | ConvertFrom-Json
        foreach ($property in $performanceBaseline.samples.PSObject.Properties) {
            $modeName = $property.Name.ToUpperInvariant()
            if ($referenceP95.ContainsKey($modeName)) {
                $referenceP95[$modeName] = [double]$property.Value.p95_ms
                $referenceSource[$modeName] = "same-session:$resolvedBaselinePath"
            }
        }
        Add-Gate 'performance baseline' 'PASS' `
            ("same-session baseline loaded: {0}" -f $resolvedBaselinePath) $performanceBaseline
    }
    foreach ($sample in $corpus.samples) {
        $samplePassed = $true
        $samplePath = Join-Path $repoRoot ([string]$sample.local_path)
        try {
            if (-not (Test-Path -LiteralPath $samplePath)) {
                $corpusBlocked = $true
                $samplePassed = $false
                throw "corpus WAV is missing: $($sample.local_path)"
            }
            $actualSha = Get-Ft8cnFileSha256 $samplePath
            if ($actualSha -ne [string]$sample.sha256) { throw "sample SHA256 mismatch: $actualSha" }
            $outputJson = Join-Path $runRoot ("perf-{0}.json" -f $sample.id)
            $null = & (Join-Path $PSScriptRoot 'measure-decoder.ps1') `
                -Executable $smokeExecutable -Mode $sample.mode -SamplePath $samplePath `
                -WarmupCount 1 -IterationCount 5 -DecodePassCount 3 `
                -MultiDecodeRoundCount 3 -DecodeSensitivity 2 -QsoSensitivity 2 `
                -LdpcIterations 200 -OutputJson $outputJson
            $measurement = Get-Content -LiteralPath $outputJson -Raw | ConvertFrom-Json

            if ($sample.mode -in @('FT8', 'FT4') -and `
                    [double]$measurement.p95_ms -gt ([double]$referenceP95[$sample.mode] * 1.03)) {
                $null = & (Join-Path $PSScriptRoot 'measure-decoder.ps1') `
                    -Executable $smokeExecutable -Mode $sample.mode -SamplePath $samplePath `
                    -WarmupCount 2 -IterationCount 15 -DecodePassCount 3 `
                    -MultiDecodeRoundCount 3 -DecodeSensitivity 2 -QsoSensitivity 2 `
                    -LdpcIterations 200 -OutputJson $outputJson
                $measurement = Get-Content -LiteralPath $outputJson -Raw | ConvertFrom-Json
            }

            $counts = @($measurement.result_counts | Select-Object -Unique)
            $hashes = @($measurement.result_sha256)
            if ($counts.Count -ne 1 -or [int]$counts[0] -ne [int]$sample.expected_result_count) {
                throw "result count mismatch: expected=$($sample.expected_result_count), actual=$($counts -join ',')"
            }
            if ($hashes.Count -ne 1 -or [string]$hashes[0] -ne [string]$sample.result_sha256) {
                throw "full result SHA256 mismatch: expected=$($sample.result_sha256), actual=$($hashes -join ',')"
            }
            $expectedCheck = Test-FtxExpectedResults `
                -Expected @($sample.expected_results) `
                -Actual @($measurement.normalized_results)
            if (-not $expectedCheck.passed) {
                $detail = @($expectedCheck.missing_or_mismatched | ForEach-Object { Format-FtxRecord $_ }) -join '; '
                throw "expected_results mismatch: $detail"
            }
            # 正确性结果可独立供 jt9 oracle 使用；性能门禁失败不应伪装成 oracle 缺失。
            $sampleMeasurements[[string]$sample.id] = $measurement
            if ($sample.mode -in @('FT8', 'FT4') -and `
                    [double]$measurement.p95_ms -gt ([double]$referenceP95[$sample.mode] * 1.03)) {
                throw ("stable p95 regression exceeds 3%: reference={0}ms, measured={1}ms" -f `
                    $referenceP95[$sample.mode], $measurement.p95_ms)
            }
            $entry = [pscustomobject]@{
                id = [string]$sample.id
                mode = [string]$sample.mode
                sample_path = $samplePath
                sample_sha256 = $actualSha
                result_count = [int]$counts[0]
                result_sha256 = [string]$hashes[0]
                p50_ms = [double]$measurement.p50_ms
                p95_ms = [double]$measurement.p95_ms
                peak_working_set_bytes = [long]$measurement.peak_working_set_bytes
                private_memory_bytes = [long]$measurement.private_memory_bytes
                reference_p95_ms = [double]$referenceP95[$sample.mode]
                reference_source = [string]$referenceSource[$sample.mode]
                p95_delta_percent = [math]::Round((([double]$measurement.p95_ms / `
                            [double]$referenceP95[$sample.mode]) - 1.0) * 100.0, 3)
                expected_results = $expectedCheck
                status = 'PASS'
            }
            $null = $sampleResults.Add($entry)
            Add-Gate ("sample {0}" -f $sample.id) 'PASS' `
                ("count={0}, sha256={1}, p50={2}ms, p95={3}ms" -f `
                    $counts[0], $hashes[0], $measurement.p50_ms, $measurement.p95_ms) $entry
        } catch {
            $samplePassed = $false
            if ($_.Exception.Message -match 'missing') { $corpusBlocked = $true } else { $hasFailure = $true }
            $entry = [pscustomobject]@{
                id = [string]$sample.id
                mode = [string]$sample.mode
                sample_path = $samplePath
                status = if ($_.Exception.Message -match 'missing') { 'BLOCKED_CORPUS' } else { 'FAIL' }
                reason = $_.Exception.Message
            }
            $null = $sampleResults.Add($entry)
            Add-Gate ("sample {0}" -f $sample.id) $entry.status $entry.reason $entry
        }
        if (-not $samplePassed) { $allSamplesPassed = $false }
    }
}

if ($hostInfrastructurePassed -and $allSamplesPassed -and -not $corpusBlocked) {
    $hostPassed = $true
    Add-Gate 'host release candidate' 'HOST_RC_PASS' 'host CTest and all corpus gates passed'
}

$oracleRoots = New-Object System.Collections.Generic.List[string]
foreach ($root in $roots) { $oracleRoots.Add($root) }
$cursor = Get-Item $repoRoot
while ($null -ne $cursor) {
    $oracleRoots.Add($cursor.FullName)
    foreach ($sibling in @(Get-ChildItem -LiteralPath $cursor.FullName -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)wsjt' })) { $oracleRoots.Add($sibling.FullName) }
    $cursor = $cursor.Parent
}
$Jt9Path = Find-OfficialJt9 -ExplicitPath $Jt9Path -CandidateRoots @($oracleRoots)
if (-not $Jt9Path) {
    $oracleBlocked = $true
    Add-Gate 'official jt9 cross-oracle' 'BLOCKED_ORACLE' 'jt9 executable was not discovered'
} elseif ($null -eq $corpus -or -not $hostInfrastructurePassed) {
    $oracleBlocked = $true
    Add-Gate 'official jt9 cross-oracle' 'BLOCKED_ORACLE' 'host corpus results are unavailable'
} else {
    $allOraclePassed = $true
    $jt9Info = [pscustomobject]@{
        path = $Jt9Path
        sha256 = Get-Ft8cnFileSha256 $Jt9Path
        version = (Get-Item -LiteralPath $Jt9Path).VersionInfo.FileVersion
        frequency_tolerance_hz = $oracleFrequencyToleranceHz
        dt_tolerance_sec = $oracleDtToleranceSec
        decode_minimum_hz = $decodeMinimumHz
        decode_maximum_hz = $decodeMaximumHz
    }
    foreach ($sample in $corpus.samples | Where-Object { $_.mode -in @('FT8', 'FT4') }) {
        $samplePath = Join-Path $repoRoot ([string]$sample.local_path)
        $bridgeMeasurement = $null
        if ($sampleMeasurements.ContainsKey([string]$sample.id)) {
            $bridgeMeasurement = $sampleMeasurements[[string]$sample.id]
        } else {
            $measurementPath = Join-Path $runRoot ("perf-{0}.json" -f $sample.id)
            if (Test-Path -LiteralPath $measurementPath) {
                $bridgeMeasurement = Get-Content -LiteralPath $measurementPath -Raw | ConvertFrom-Json
            }
        }
        if (-not (Test-Path -LiteralPath $samplePath) -or $null -eq $bridgeMeasurement) {
            $allOraclePassed = $false
            $oracleBlocked = $true
            Add-Gate ("official jt9 {0}" -f $sample.id) 'BLOCKED_ORACLE' `
                ("matching bridge result is unavailable: wav={0}, measurement={1}" -f `
                    (Test-Path -LiteralPath $samplePath), ($null -ne $bridgeMeasurement))
            continue
        }
        try {
            $oracle = Invoke-OfficialOracle -Executable $Jt9Path -Mode $sample.mode `
                -SamplePath $samplePath -WorkDir (Join-Path $runRoot ("jt9-{0}" -f $sample.id))
            $officialAll = @($oracle.records)
            $bridgeAll = @($bridgeMeasurement.normalized_results)
            $officialInBand = @(Select-FtxDecodeBandRecords -Records $officialAll -Mode $sample.mode `
                -MinimumHz $decodeMinimumHz -MaximumHz $decodeMaximumHz)
            $bridgeInBand = @(Select-FtxDecodeBandRecords -Records $bridgeAll -Mode $sample.mode `
                -MinimumHz $decodeMinimumHz -MaximumHz $decodeMaximumHz)
            $comparison = Compare-FtxDecodeResults -Official $officialInBand -Ft8cn $bridgeInBand `
                -FrequencyToleranceHz $oracleFrequencyToleranceHz `
                -DtToleranceSec $oracleDtToleranceSec
            $entry = [pscustomobject]@{
                sample_id = [string]$sample.id
                mode = [string]$sample.mode
                jt9 = $jt9Info
                comparison = $comparison
                official_out_of_band = @($officialAll | Where-Object {
                    $record = $_
                    -not (@(Select-FtxDecodeBandRecords -Records @($record) -Mode $sample.mode `
                        -MinimumHz $decodeMinimumHz -MaximumHz $decodeMaximumHz).Count)
                })
                ft8cn_out_of_band = @($bridgeAll | Where-Object {
                    $record = $_
                    -not (@(Select-FtxDecodeBandRecords -Records @($record) -Mode $sample.mode `
                        -MinimumHz $decodeMinimumHz -MaximumHz $decodeMaximumHz).Count)
                })
                stdout_path = $oracle.stdout_path
                decoded_path = $oracle.decoded_path
            }
            $null = $oracleResults.Add($entry)
            if (-not $comparison.passed) {
                Write-OracleDifferences $comparison
                throw 'official jt9 and FT8CN bridge results differ'
            }
            Add-Gate ("official jt9 {0}" -f $sample.id) 'PASS' `
                ("{0} records match as a multiset" -f $comparison.official_count) $entry
        } catch {
            $allOraclePassed = $false
            $hasFailure = $true
            Add-Gate ("official jt9 {0}" -f $sample.id) 'FAIL' $_.Exception.Message
        }
    }
    if ($allOraclePassed) {
        $oraclePassed = $true
        Add-Gate 'official jt9 cross-oracle' 'PASS' 'FT8 and FT4 strict comparisons passed' $jt9Info
    }
}

if ($SkipSanitizers) {
    $sanitizerBlocked = $true
    Add-Gate 'host ASan/UBSan' 'BLOCKED_SANITIZER' 'disabled by -SkipSanitizers'
} elseif ($MsysRoot -and $hostInfrastructurePassed) {
    $gxx = Join-Path $MsysRoot 'ucrt64\bin\g++.exe'
    $asan = if (Test-Path $gxx) { (& $gxx -print-file-name=libasan.a).Trim() } else { '' }
    $ubsan = if (Test-Path $gxx) { (& $gxx -print-file-name=libubsan.a).Trim() } else { '' }
    if (-not [System.IO.Path]::IsPathRooted($asan) -or -not (Test-Path $asan) -or `
            -not [System.IO.Path]::IsPathRooted($ubsan) -or -not (Test-Path $ubsan)) {
        $sanitizerBlocked = $true
        Add-Gate 'host ASan/UBSan' 'BLOCKED_SANITIZER' 'MSYS2 sanitizer runtimes were not found'
    } else {
        try {
            $sanitizerBuild = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build-sanitized'
            & (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\build_host_probe.ps1') `
                -BuildDir $sanitizerBuild -BuildType Debug -Optimization O2 `
                -MsysRoot $MsysRoot -FftwRoot $FftwRoot -EnableSanitizers
            Assert-LastExitCode 'sanitized host build'
            $ctest = Join-Path $MsysRoot 'ucrt64\bin\ctest.exe'
            $oldPath = $env:PATH
            try {
                $env:PATH = (Join-Path $MsysRoot 'ucrt64\bin') + ';' + `
                    (Join-Path $MsysRoot 'usr\bin') + ';' + $oldPath
                & $ctest --test-dir $sanitizerBuild --output-on-failure
                Assert-LastExitCode 'sanitized CTest'
            } finally {
                $env:PATH = $oldPath
            }
            $sanitizerPassed = $true
            Add-Gate 'host ASan/UBSan' 'PASS' 'sanitized C/C++ build and CTest passed'
        } catch {
            $hasFailure = $true
            Add-Gate 'host ASan/UBSan' 'FAIL' $_.Exception.Message
        }
    }
} else {
    $sanitizerBlocked = $true
    Add-Gate 'host ASan/UBSan' 'BLOCKED_SANITIZER' 'host toolchain is unavailable'
}

if ($SkipAndroidBuild) {
    Add-Gate 'Android build' 'FAIL' 'disabled by -SkipAndroidBuild; release qualification requires Android builds'
    $hasFailure = $true
} elseif ($null -ne $tools) {
    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:PATH
    try {
        $androidWatch = [Diagnostics.Stopwatch]::StartNew()
        $env:JAVA_HOME = $tools.JavaHome
        $env:PATH = (Join-Path $tools.JavaHome 'bin') + ';' + $oldPath
        Push-Location $repoRoot
        try {
            & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease `
                :app:assembleDebugAndroidTest
            Assert-LastExitCode 'Gradle test/debug/release build'
        } finally {
            Pop-Location
        }
        $androidWatch.Stop()
        $apkPaths = [ordered]@{
            debug = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
            release = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
            android_test = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
        }
        $apkEvidence = [ordered]@{ elapsed_ms = [math]::Round($androidWatch.Elapsed.TotalMilliseconds, 3) }
        foreach ($entry in $apkPaths.GetEnumerator()) {
            if (-not (Test-Path -LiteralPath $entry.Value)) { throw "built APK is missing: $($entry.Value)" }
            $file = Get-Item -LiteralPath $entry.Value
            $apkEvidence[$entry.Key] = [ordered]@{
                path = $entry.Value
                size_bytes = $file.Length
                sha256 = (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
        $androidPassed = $true
        Add-Gate 'Android build' 'PASS' 'unit tests, debug/release APK and internal test APK completed' `
            ([pscustomobject]$apkEvidence)
    } catch {
        $hasFailure = $true
        Add-Gate 'Android build' 'FAIL' $_.Exception.Message
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:PATH = $oldPath
    }
}

if ($SkipDeviceGate) {
    $deviceBlocked = $true
    Add-Gate 'Android device benchmark' 'BLOCKED_DEVICE' 'disabled by -SkipDeviceGate'
} elseif ($null -eq $tools) {
    $deviceBlocked = $true
    Add-Gate 'Android device benchmark' 'BLOCKED_DEVICE' 'ADB tool is unavailable'
} else {
    $deviceLines = @(& $tools.Adb devices | Where-Object { $_ -match "\tdevice$" })
    $deviceScript = Join-Path $PSScriptRoot 'run-device-benchmark.ps1'
    if ($deviceLines.Count -eq 0) {
        $deviceBlocked = $true
        Add-Gate 'Android device benchmark' 'BLOCKED_DEVICE' 'no authorized ADB device is connected'
    } elseif (-not (Test-Path -LiteralPath $deviceScript)) {
        $deviceBlocked = $true
        Add-Gate 'Android device benchmark' 'BLOCKED_DEVICE' 'internal device benchmark harness is unavailable'
    } else {
        try {
            $deviceReport = Join-Path $runRoot 'device-benchmark.json'
            & $deviceScript -AdbPath $tools.Adb -JavaHome $tools.JavaHome `
                -CorpusManifest $corpusPath -OutputJson $deviceReport
            Assert-LastExitCode 'Android device benchmark'
            $deviceData = Get-Content -LiteralPath $deviceReport -Raw | ConvertFrom-Json
            if (-not $deviceData.passed) { throw 'device benchmark report did not pass' }
            if ($deviceData.PSObject.Properties.Name -notcontains 'q65_streaming' `
                    -or -not $deviceData.q65_streaming.passed) {
                throw 'Q65 bounded streaming device gate did not pass'
            }
            $devicePassed = $true
            $q65StreamingPassed = $true
            $q65StreamingBlocked = $false
            Add-Gate 'Android device benchmark' 'PASS' 'debug/release native device gates passed' $deviceData
        } catch {
            $hasFailure = $true
            Add-Gate 'Android device benchmark' 'FAIL' $_.Exception.Message
        }
    }
}

if ($q65StreamingPassed) {
    Add-Gate 'Q65 long-period streaming' 'PASS' `
        'production RX uses 4096-sample chunks and a native-owned final 12 kHz frame with no final Java array; TX uses bounded MODE_STREAM chunks'
} else {
    Add-Gate 'Q65 long-period streaming' 'BLOCKED_Q65_STREAMING' `
        'production code and internal tests exist, but Debug/Release device evidence is unavailable'
}

$finalStates = New-Object System.Collections.Generic.List[string]
if ($hostPassed) { $finalStates.Add('HOST_RC_PASS') }
if ($oracleBlocked) { $finalStates.Add('BLOCKED_ORACLE') }
if ($deviceBlocked) { $finalStates.Add('BLOCKED_DEVICE') }
if ($sanitizerBlocked) { $finalStates.Add('BLOCKED_SANITIZER') }
if ($corpusBlocked) { $finalStates.Add('BLOCKED_CORPUS') }
if ($q65StreamingBlocked) { $finalStates.Add('BLOCKED_Q65_STREAMING') }
if ($hasFailure) { $finalStates.Add('FAIL') }
if ($hostPassed -and $oraclePassed -and $androidPassed -and $devicePassed `
        -and -not $q65StreamingBlocked -and -not $hasFailure) {
    $finalStates.Add('DEVICE_RELEASE_PASS')
}
if ($finalStates.Count -eq 0) { $finalStates.Add('FAIL'); $hasFailure = $true }

$duration = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalSeconds, 3)
Add-Gate 'verification summary' ($finalStates -join ',') ("elapsed={0}s" -f $duration)
Write-VerificationReport -FinalStates $finalStates.ToArray()
Write-Host ("FINAL_STATES={0}" -f ($finalStates -join ','))

if ($hasFailure) { exit 1 }
if ($finalStates -contains 'DEVICE_RELEASE_PASS') { exit 0 }
exit 2
