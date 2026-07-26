$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'verification-common.ps1')

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERT_TRUE failed: $Message" }
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "ASSERT_EQUAL failed: $Message; expected=[$Expected], actual=[$Actual]"
    }
}

$bridgeText = @'
[FT8] rate=12000 samples=180000 results=2
  #1 sync=2.0 snr=-20 dt=0.25 freq=1000.4 nap=0 text=CQ TEST AA00
  #0 sync=3.0 snr=-10 dt=-0.12 freq=500.6 nap=0 text=K1ABC W1XYZ RR73
'@
$bridge = @(ConvertFrom-Ft8cnBridgeOutput $bridgeText)
Assert-Equal 2 $bridge.Count 'bridge parser count'
Assert-Equal 'CQ TEST AA00' $bridge[0].message 'stable message sort'
Assert-Equal 500.6 $bridge[1].frequency_hz 'bridge frequency parser'

$officialText = @'
000000 -10 -0.1 501 ~ K1ABC W1XYZ RR73
000000 -20 0.3 1000 ~ CQ TEST AA00
'@
$official = @(ConvertFrom-OfficialJt9Output $officialText -Mode FT8)
Assert-Equal 2 $official.Count 'official stdout parser count'
$comparison = Compare-FtxDecodeResults $official $bridge -FrequencyToleranceHz 1.0 -DtToleranceSec 0.06
Assert-True $comparison.passed 'rounded official values should match bridge values'

$decodedText = '000000 10 -20 0.3 1000. 0 CQ TEST AA00 FT8'
$decoded = @(ConvertFrom-OfficialJt9Output $decodedText -Mode FT8)
Assert-Equal 1 $decoded.Count 'decoded.txt parser count'
Assert-Equal 'CQ TEST AA00' $decoded[0].message 'decoded.txt message parser'

$duplicateOfficial = @(
    New-FtxDecodeRecord -Message 'CQ DUP AA00' -FrequencyHz 1000 -DtSec 0.1 -SourceIndex 0
    New-FtxDecodeRecord -Message 'CQ DUP AA00' -FrequencyHz 1200 -DtSec 0.2 -SourceIndex 1
)
$duplicateFt8cn = @(
    New-FtxDecodeRecord -Message 'CQ DUP AA00' -FrequencyHz 1000 -DtSec 0.1 -SourceIndex 0
)
$duplicateDiff = Compare-FtxDecodeResults $duplicateOfficial $duplicateFt8cn
Assert-True (-not $duplicateDiff.passed) 'duplicate multiplicity must not be collapsed'
Assert-True $duplicateDiff.count_mismatch 'duplicate count mismatch must be reported'
Assert-Equal 1 $duplicateDiff.only_in_official.Count 'duplicate extra must stay only-in-official'

$onlyFt8cn = Compare-FtxDecodeResults @() $duplicateFt8cn
Assert-Equal 1 $onlyFt8cn.only_in_ft8cn.Count 'FT8CN-only result must be reported'

$metricMismatch = Compare-FtxDecodeResults `
    @(New-FtxDecodeRecord -Message 'CQ TEST AA00' -FrequencyHz 1000 -DtSec 0.1) `
    @(New-FtxDecodeRecord -Message 'CQ TEST AA00' -FrequencyHz 1002 -DtSec 0.2)
Assert-Equal 1 $metricMismatch.metric_mismatches.Count 'frequency/DT mismatch must be reported'

$ft4Band = @(Select-FtxDecodeBandRecords -Mode FT4 -Records @(
    New-FtxDecodeRecord -Message 'IN BAND' -FrequencyHz 2937.4 -DtSec 0
    New-FtxDecodeRecord -Message 'OUT BAND' -FrequencyHz 2995 -DtSec 0
))
Assert-Equal 1 $ft4Band.Count 'FT4 occupied bandwidth must fit inside 0-3000 Hz'
Assert-Equal 'IN BAND' $ft4Band[0].message 'FT4 in-band record must remain'

$expected = @([pscustomobject]@{ text = 'CQ TEST AA00'; frequency_hz = 1000.4; dt_sec = 0.25 })
$expectedPass = Test-FtxExpectedResults $expected $bridge
Assert-True $expectedPass.passed 'manifest expected result should match exact bridge values'
$expected[0].frequency_hz = 1001.0
$expectedFail = Test-FtxExpectedResults $expected $bridge
Assert-True (-not $expectedFail.passed) 'manifest frequency mismatch must fail'

Write-Host 'verification-common tests: PASS'
