Set-StrictMode -Version Latest

function Resolve-GradleApkOutput {
    param([Parameter(Mandatory = $true)][string]$OutputDirectory)

    $resolvedDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
    $metadataPath = Join-Path $resolvedDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Gradle APK metadata is missing: $metadataPath"
    }

    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $elements = @($metadata.elements)
    if ($elements.Count -ne 1) {
        throw "Expected one Gradle APK output in $metadataPath, found $($elements.Count)"
    }
    $outputFile = [string]$elements[0].outputFile
    if (-not $outputFile -or [System.IO.Path]::IsPathRooted($outputFile)) {
        throw "Gradle APK outputFile must be a relative file name: $outputFile"
    }

    $apkPath = [System.IO.Path]::GetFullPath((Join-Path $resolvedDirectory $outputFile))
    $directoryPrefix = $resolvedDirectory.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $apkPath.StartsWith($directoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Gradle APK output escapes its output directory: $outputFile"
    }
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "Gradle APK output is missing: $apkPath"
    }
    return $apkPath
}

function Find-AndroidApkSigner {
    param([string]$AndroidSdkRoot = '')

    $candidateRoots = @($AndroidSdkRoot, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
        Where-Object { $_ } | Select-Object -Unique
    foreach ($root in $candidateRoots) {
        $buildToolsRoot = Join-Path $root 'build-tools'
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) { continue }
        foreach ($directory in @(Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
                Sort-Object Name -Descending)) {
            foreach ($name in @('apksigner.bat', 'apksigner')) {
                $candidate = Join-Path $directory.FullName $name
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            }
        }
    }

    foreach ($name in @('apksigner.bat', 'apksigner')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command -and (Test-Path -LiteralPath $command.Source -PathType Leaf)) {
            return $command.Source
        }
    }
    return $null
}

function Get-AndroidApkSignatureStatus {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [string]$AndroidSdkRoot = ''
    )

    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "APK is missing: $ApkPath"
    }
    $apkSigner = Find-AndroidApkSigner -AndroidSdkRoot $AndroidSdkRoot
    if (-not $apkSigner) {
        return [pscustomobject]@{
            checked = $false
            signed = $false
            tool = $null
            exit_code = $null
            certificate_sha256 = $null
            detail = 'apksigner was not discovered'
        }
    }

    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $apkSigner verify --verbose --print-certs $ApkPath 2>&1 |
            ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $certificateSha256 = $null
    foreach ($line in $output) {
        if ($line -match 'certificate SHA-256 digest:\s*(?<digest>[0-9a-fA-F:]+)') {
            $certificateSha256 = $matches.digest.Replace(':', '').ToLowerInvariant()
            break
        }
    }
    return [pscustomobject]@{
        checked = $true
        signed = $exitCode -eq 0
        tool = $apkSigner
        exit_code = $exitCode
        certificate_sha256 = $certificateSha256
        detail = ($output -join "`n")
    }
}

function Normalize-FtxMessage {
    param([AllowNull()][string]$Message)

    if ($null -eq $Message) { return '' }
    return [regex]::Replace($Message.Trim(), '\s+', ' ')
}

function New-FtxDecodeRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][double]$FrequencyHz,
        [Parameter(Mandatory = $true)][double]$DtSec,
        [int]$SourceIndex = 0
    )

    return [pscustomobject]@{
        message = Normalize-FtxMessage $Message
        frequency_hz = [Math]::Round($FrequencyHz, 6)
        dt_sec = [Math]::Round($DtSec, 6)
        source_index = $SourceIndex
    }
}

function Sort-FtxDecodeRecords {
    param([AllowEmptyCollection()][object[]]$Records = @())

    $sortProperties = @(
        @{ Expression = { [string]$_.message }; Ascending = $true }
        @{ Expression = { [double]$_.frequency_hz }; Ascending = $true }
        @{ Expression = { [double]$_.dt_sec }; Ascending = $true }
        @{ Expression = { [int]$_.source_index }; Ascending = $true }
    )
    return @($Records | Sort-Object -Property $sortProperties)
}

function ConvertFrom-Ft8cnBridgeOutput {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $records = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^\s*#(?<index>\d+)\s+sync=[-+\d.eE]+\s+snr=[-+\d]+\s+dt=(?<dt>[-+\d.eE]+)\s+freq=(?<freq>[-+\d.eE]+)\s+nap=\d+\s+text=(?<message>.*\S)\s*$') {
            $records.Add((New-FtxDecodeRecord `
                -Message $matches.message `
                -FrequencyHz ([double]::Parse($matches.freq, [Globalization.CultureInfo]::InvariantCulture)) `
                -DtSec ([double]::Parse($matches.dt, [Globalization.CultureInfo]::InvariantCulture)) `
                -SourceIndex ([int]$matches.index)))
        }
    }
    return @(Sort-FtxDecodeRecords -Records ($records.ToArray()))
}

function ConvertFrom-OfficialJt9Output {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [ValidateSet('', 'FT8', 'FT4')][string]$Mode = ''
    )

    $records = New-Object System.Collections.Generic.List[object]
    $sourceIndex = 0
    foreach ($line in ($Text -split "`r?`n")) {
        $record = $null
        # jt9 标准输出：HHMMSS SNR DT FREQ ~ MESSAGE
        if ($line -match '^\s*\d{6}\s+[-+\d.]+\s+(?<dt>[-+\d.]+)\s+(?<freq>[-+\d.]+)\s+[~+]\s+(?<message>.*\S)\s*$') {
            $record = New-FtxDecodeRecord `
                -Message $matches.message `
                -FrequencyHz ([double]::Parse($matches.freq, [Globalization.CultureInfo]::InvariantCulture)) `
                -DtSec ([double]::Parse($matches.dt, [Globalization.CultureInfo]::InvariantCulture)) `
                -SourceIndex $sourceIndex
        # decoded.txt：HHMMSS SYNC SNR DT FREQ DRIFT MESSAGE MODE
        } elseif ($line -match '^\s*\d{6}\s+[-+\d.]+\s+[-+\d.]+\s+(?<dt>[-+\d.]+)\s+(?<freq>[-+\d.]+)\s+[-+\d.]+\s+(?<message>.+?)\s+(?<mode>FT8|FT4)\s*$') {
            if (-not $Mode -or $matches.mode -eq $Mode) {
                $record = New-FtxDecodeRecord `
                    -Message $matches.message `
                    -FrequencyHz ([double]::Parse($matches.freq, [Globalization.CultureInfo]::InvariantCulture)) `
                    -DtSec ([double]::Parse($matches.dt, [Globalization.CultureInfo]::InvariantCulture)) `
                    -SourceIndex $sourceIndex
            }
        }
        if ($null -ne $record) {
            $records.Add($record)
            $sourceIndex++
        }
    }
    return @(Sort-FtxDecodeRecords -Records ($records.ToArray()))
}

function Format-FtxRecord {
    param([Parameter(Mandatory = $true)][object]$Record)

    return ('{0} | freq={1:F3}Hz | dt={2:F3}s' -f `
        $Record.message, [double]$Record.frequency_hz, [double]$Record.dt_sec)
}

function Select-FtxDecodeBandRecords {
    param(
        [AllowEmptyCollection()][object[]]$Records = @(),
        [Parameter(Mandatory = $true)][ValidateSet('FT8', 'FT4')][string]$Mode,
        [double]$MinimumHz = 0.0,
        [double]$MaximumHz = 3000.0
    )

    $occupiedBandwidthHz = if ($Mode -eq 'FT8') {
        7.0 * 6.25
    } else {
        3.0 * (12000.0 / 576.0)
    }
    return @(Sort-FtxDecodeRecords @($Records | Where-Object {
        [double]$_.frequency_hz -ge $MinimumHz -and
        ([double]$_.frequency_hz + $occupiedBandwidthHz) -le $MaximumHz
    }))
}

function Compare-FtxDecodeResults {
    param(
        [AllowEmptyCollection()][object[]]$Official = @(),
        [AllowEmptyCollection()][object[]]$Ft8cn = @(),
        [ValidateRange(0.0, 1000.0)][double]$FrequencyToleranceHz = 3.2,
        [ValidateRange(0.0, 10.0)][double]$DtToleranceSec = 0.06
    )

    $officialSorted = @(Sort-FtxDecodeRecords $Official)
    $ft8cnSorted = @(Sort-FtxDecodeRecords $Ft8cn)
    $officialGroups = @{}
    $ft8cnGroups = @{}
    foreach ($record in $officialSorted) {
        $key = Normalize-FtxMessage ([string]$record.message)
        if (-not $officialGroups.ContainsKey($key)) { $officialGroups[$key] = New-Object System.Collections.ArrayList }
        $null = $officialGroups[$key].Add($record)
    }
    foreach ($record in $ft8cnSorted) {
        $key = Normalize-FtxMessage ([string]$record.message)
        if (-not $ft8cnGroups.ContainsKey($key)) { $ft8cnGroups[$key] = New-Object System.Collections.ArrayList }
        $null = $ft8cnGroups[$key].Add($record)
    }

    $keys = @($officialGroups.Keys + $ft8cnGroups.Keys | Sort-Object -Unique)
    $onlyOfficial = New-Object System.Collections.Generic.List[object]
    $onlyFt8cn = New-Object System.Collections.Generic.List[object]
    $metricMismatches = New-Object System.Collections.Generic.List[object]
    foreach ($key in $keys) {
        $left = @()
        $right = @()
        if ($officialGroups.ContainsKey($key)) { $left = @($officialGroups[$key].ToArray()) }
        if ($ft8cnGroups.ContainsKey($key)) { $right = @($ft8cnGroups[$key].ToArray()) }
        $paired = [Math]::Min($left.Count, $right.Count)
        for ($index = 0; $index -lt $paired; $index++) {
            $frequencyDelta = [Math]::Abs([double]$left[$index].frequency_hz - [double]$right[$index].frequency_hz)
            $dtDelta = [Math]::Abs([double]$left[$index].dt_sec - [double]$right[$index].dt_sec)
            if ($frequencyDelta -gt $FrequencyToleranceHz -or $dtDelta -gt $DtToleranceSec) {
                $metricMismatches.Add([pscustomobject]@{
                    message = $key
                    official_frequency_hz = [double]$left[$index].frequency_hz
                    ft8cn_frequency_hz = [double]$right[$index].frequency_hz
                    frequency_delta_hz = [Math]::Round($frequencyDelta, 6)
                    official_dt_sec = [double]$left[$index].dt_sec
                    ft8cn_dt_sec = [double]$right[$index].dt_sec
                    dt_delta_sec = [Math]::Round($dtDelta, 6)
                })
            }
        }
        for ($index = $paired; $index -lt $left.Count; $index++) { $onlyOfficial.Add($left[$index]) }
        for ($index = $paired; $index -lt $right.Count; $index++) { $onlyFt8cn.Add($right[$index]) }
    }

    $countMismatch = $officialSorted.Count -ne $ft8cnSorted.Count
    return [pscustomobject]@{
        passed = (-not $countMismatch -and $onlyOfficial.Count -eq 0 -and `
            $onlyFt8cn.Count -eq 0 -and $metricMismatches.Count -eq 0)
        official_count = $officialSorted.Count
        ft8cn_count = $ft8cnSorted.Count
        count_mismatch = $countMismatch
        frequency_tolerance_hz = $FrequencyToleranceHz
        dt_tolerance_sec = $DtToleranceSec
        only_in_official = @($onlyOfficial.ToArray())
        only_in_ft8cn = @($onlyFt8cn.ToArray())
        metric_mismatches = @($metricMismatches.ToArray())
    }
}

function Test-FtxExpectedResults {
    param(
        [AllowEmptyCollection()][object[]]$Expected = @(),
        [AllowEmptyCollection()][object[]]$Actual = @(),
        [ValidateRange(0.0, 1000.0)][double]$FrequencyToleranceHz = 0.11,
        [ValidateRange(0.0, 10.0)][double]$DtToleranceSec = 0.011
    )

    $actualSorted = @(Sort-FtxDecodeRecords $Actual)
    $used = New-Object bool[] $actualSorted.Count
    $missing = New-Object System.Collections.Generic.List[object]
    foreach ($item in $Expected) {
        $message = if ($item.PSObject.Properties.Name -contains 'text') {
            Normalize-FtxMessage ([string]$item.text)
        } else {
            Normalize-FtxMessage ([string]$item.message)
        }
        $expectedFrequency = [double]$item.frequency_hz
        $expectedDt = [double]$item.dt_sec
        $bestIndex = -1
        $bestDistance = [double]::PositiveInfinity
        for ($index = 0; $index -lt $actualSorted.Count; $index++) {
            if ($used[$index] -or [string]$actualSorted[$index].message -ne $message) { continue }
            $frequencyDelta = [Math]::Abs([double]$actualSorted[$index].frequency_hz - $expectedFrequency)
            $dtDelta = [Math]::Abs([double]$actualSorted[$index].dt_sec - $expectedDt)
            if ($frequencyDelta -le $FrequencyToleranceHz -and $dtDelta -le $DtToleranceSec) {
                $distance = $frequencyDelta + $dtDelta
                if ($distance -lt $bestDistance) {
                    $bestDistance = $distance
                    $bestIndex = $index
                }
            }
        }
        if ($bestIndex -ge 0) {
            $used[$bestIndex] = $true
        } else {
            $missing.Add((New-FtxDecodeRecord -Message $message -FrequencyHz $expectedFrequency -DtSec $expectedDt))
        }
    }

    return [pscustomobject]@{
        passed = $missing.Count -eq 0
        expected_count = $Expected.Count
        matched_count = $Expected.Count - $missing.Count
        frequency_tolerance_hz = $FrequencyToleranceHz
        dt_tolerance_sec = $DtToleranceSec
        missing_or_mismatched = @($missing.ToArray())
    }
}
