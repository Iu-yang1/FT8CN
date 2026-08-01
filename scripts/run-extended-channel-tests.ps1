param(
    [string]$Executable = '',
    [ValidateRange(1, 1000000)][int]$NoiseSlotsPerMode = 100,
    [ValidateRange(1, 10000)][int]$SnrTrials = 10,
    [string]$OutputJson = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')
if (-not $Executable) {
    $Executable = Join-Path $repoRoot `
        'app\src\main\cpp\wsjtx3\host\build-release-o2\ftx_extended_channel_test.exe'
}
$Executable = (Resolve-Path $Executable).ProviderPath
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
$msysRoot = Find-Ft8cnDirectory -CandidateRoots $roots -RelativePatterns @('msys64') `
    -RequiredChild 'ucrt64\bin\libgfortran-5.dll'
if (-not $msysRoot) { throw 'MSYS2 runtime was not found.' }

$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $Executable
$startInfo.WorkingDirectory = $repoRoot
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.EnvironmentVariables['PATH'] = (Join-Path $msysRoot 'ucrt64\bin') + ';' + `
    (Join-Path $msysRoot 'usr\bin') + ';' + $env:PATH
$startInfo.EnvironmentVariables['FT8CN_EXTENDED_NOISE_SLOTS'] = [string]$NoiseSlotsPerMode
$startInfo.EnvironmentVariables['FT8CN_EXTENDED_SNR_TRIALS'] = [string]$SnrTrials
$watch = [Diagnostics.Stopwatch]::StartNew()
$process = [Diagnostics.Process]::Start($startInfo)
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$watch.Stop()
$stdout = $stdoutTask.Result
$stderr = $stderrTask.Result
if ($process.ExitCode -ne 0) { throw "Extended channel test failed:`n$stdout`n$stderr" }

$snrRows = New-Object System.Collections.ArrayList
$noiseRows = New-Object System.Collections.ArrayList
foreach ($line in ($stdout -split "`r?`n")) {
    if ($line -match '^SNR_SWEEP mode=(FT8|FT4) snr_db=([-\d.]+) detected=(\d+) trials=(\d+)$') {
        $null = $snrRows.Add([pscustomobject]@{
            mode = $matches[1]
            snr_db = [double]$matches[2]
            detected = [int]$matches[3]
            trials = [int]$matches[4]
        })
    } elseif ($line -match '^NOISE_MONTE_CARLO mode=(FT8|FT4) slots=(\d+) equivalent_hours=([\d.]+) false_decodes=(\d+)$') {
        $null = $noiseRows.Add([pscustomobject]@{
            mode = $matches[1]
            slots = [int]$matches[2]
            equivalent_hours = [double]$matches[3]
            false_decodes = [int]$matches[4]
        })
    }
}
$result = [ordered]@{
    schema_version = 1
    passed = $true
    elapsed_ms = [Math]::Round($watch.Elapsed.TotalMilliseconds, 3)
    snr_sweep = @($snrRows.ToArray())
    pure_noise = @($noiseRows.ToArray())
    stdout = $stdout.Trim()
}
if ($OutputJson) {
    $directory = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputJson))
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputJson -Encoding UTF8
}
$result | ConvertTo-Json -Depth 8
