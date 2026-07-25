param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,
    [Parameter(Mandatory = $true)]
    [ValidateSet('FT8', 'FT4', 'Q65')]
    [string]$Mode,
    [Parameter(Mandatory = $true)]
    [string]$SamplePath,
    [ValidateRange(0, 20)]
    [int]$WarmupCount = 1,
    [ValidateRange(1, 50)]
    [int]$IterationCount = 5,
    [ValidateRange(5, 3600)]
    [int]$TimeoutSeconds = 180,
    [ValidateRange(1, 5)]
    [int]$DecodePassCount = 3,
    [ValidateRange(1, 5)]
    [int]$MultiDecodeRoundCount = 3,
    [ValidateRange(0, 2)]
    [int]$DecodeSensitivity = 2,
    [ValidateRange(0, 2)]
    [int]$QsoSensitivity = 2,
    [ValidateRange(1, 500)]
    [int]$LdpcIterations = 200,
    [bool]$EnableEarlyDecode = $true,
    [bool]$EnableWidebandDxSearch = $true,
    [string]$OutputJson = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')
$Executable = (Resolve-Path $Executable).ProviderPath
$SamplePath = (Resolve-Path $SamplePath).ProviderPath

$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
$msysRoot = Find-Ft8cnDirectory -CandidateRoots $roots -RelativePatterns @('msys64') `
    -RequiredChild 'ucrt64\bin\libgfortran-5.dll'
if (-not $msysRoot) { throw 'MSYS2 runtime containing libgfortran was not found.' }
$runtimePath = "$(Join-Path $msysRoot 'ucrt64\bin');$(Join-Path $msysRoot 'usr\bin');$env:PATH"

function Get-Percentile([double[]]$Values, [double]$Percentile) {
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    return [double]$sorted[$index]
}

function Invoke-DecodeRun([bool]$Warmup) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Executable
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.EnvironmentVariables['PATH'] = $runtimePath
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_RUN_FT8'] = if ($Mode -eq 'FT8') { '1' } else { '0' }
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_RUN_FT4'] = if ($Mode -eq 'FT4') { '1' } else { '0' }
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_RUN_Q65'] = if ($Mode -eq 'Q65') { '1' } else { '0' }
    $startInfo.EnvironmentVariables["FT8CN_SMOKE_${Mode}_PATH"] = $SamplePath
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_PASSES'] = [string]$DecodePassCount
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_ROUNDS'] = [string]$MultiDecodeRoundCount
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_DECODE_SENSITIVITY'] = [string]$DecodeSensitivity
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_QSO_SENSITIVITY'] = [string]$QsoSensitivity
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_EARLY'] = if ($EnableEarlyDecode) { '1' } else { '0' }
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_WIDEBAND'] = if ($EnableWidebandDxSearch) { '1' } else { '0' }
    $startInfo.EnvironmentVariables['FT8CN_SMOKE_LDPC'] = [string]$LdpcIterations
    $startInfo.EnvironmentVariables['FT8CN_PHASE_TRACE'] = '0'

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $peakWorkingSet = 0L
    $peakPagedMemory = 0L
    $peakPrivateMemory = 0L
    while (-not $process.WaitForExit(20)) {
        $process.Refresh()
        $peakWorkingSet = [Math]::Max($peakWorkingSet, [long]$process.WorkingSet64)
        $peakPagedMemory = [Math]::Max($peakPagedMemory, [long]$process.PagedMemorySize64)
        $peakPrivateMemory = [Math]::Max($peakPrivateMemory, [long]$process.PrivateMemorySize64)
        if ($stopwatch.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
            $process.Kill()
            $process.WaitForExit()
            throw "$Mode decoder timed out after $TimeoutSeconds seconds."
        }
    }
    $stopwatch.Stop()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    if ($process.ExitCode -ne 0) {
        throw "Decoder run failed with exit code $($process.ExitCode):`n$stdout`n$stderr"
    }

    $summaryPattern = "\[$Mode\].*results=(\d+)"
    $summaryMatch = [regex]::Match($stdout, $summaryPattern)
    if (-not $summaryMatch.Success) { throw "Missing $Mode result summary:`n$stdout" }
    $resultLines = @($stdout -split "`r?`n" | Where-Object { $_ -match '^\s+#\d+\s' })
    $normalized = ($resultLines -join "`n")
    [pscustomobject]@{
        warmup = $Warmup
        elapsed_ms = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
        results = [int]$summaryMatch.Groups[1].Value
        result_sha256 = Get-Ft8cnStringSha256 $normalized
        peak_working_set_bytes = $peakWorkingSet
        peak_paged_memory_bytes = $peakPagedMemory
        private_memory_bytes = $peakPrivateMemory
    }
}

for ($index = 0; $index -lt $WarmupCount; $index++) {
    Invoke-DecodeRun $true | Out-Null
}
$runs = @()
for ($index = 0; $index -lt $IterationCount; $index++) {
    $runs += Invoke-DecodeRun $false
}
$elapsed = @($runs | ForEach-Object { [double]$_.elapsed_ms })
$result = [pscustomobject]@{
    mode = $Mode
    sample_path = $SamplePath
    sample_sha256 = Get-Ft8cnFileSha256 $SamplePath
    warmup_count = $WarmupCount
    iteration_count = $IterationCount
    options = [pscustomobject]@{
        passes = $DecodePassCount
        rounds = $MultiDecodeRoundCount
        decode_sensitivity = $DecodeSensitivity
        qso_sensitivity = $QsoSensitivity
        ldpc_iterations = $LdpcIterations
        early_decode = $EnableEarlyDecode
        wideband_dx = $EnableWidebandDxSearch
    }
    result_counts = @($runs | ForEach-Object { $_.results })
    result_sha256 = @($runs | ForEach-Object { $_.result_sha256 } | Select-Object -Unique)
    p50_ms = [Math]::Round((Get-Percentile $elapsed 0.50), 3)
    p95_ms = [Math]::Round((Get-Percentile $elapsed 0.95), 3)
    peak_working_set_bytes = [long](($runs | Measure-Object peak_working_set_bytes -Maximum).Maximum)
    peak_paged_memory_bytes = [long](($runs | Measure-Object peak_paged_memory_bytes -Maximum).Maximum)
    private_memory_bytes = [long](($runs | Measure-Object private_memory_bytes -Maximum).Maximum)
    runs = $runs
}
$json = $result | ConvertTo-Json -Depth 6
if ($OutputJson) {
    $outputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputJson))
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    Set-Content -LiteralPath $outputPath -Value $json -Encoding UTF8
}
$json
