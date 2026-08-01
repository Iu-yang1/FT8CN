param(
    [string]$RepoRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
} else {
    $RepoRoot = (Resolve-Path $RepoRoot).ProviderPath
}

$thirdPartyRoot = Join-Path $RepoRoot 'third_party'
$matrixPath = Join-Path $RepoRoot 'docs\third-party\license-matrix.md'
$sbomPath = Join-Path $RepoRoot 'docs\third-party\sbom.cdx.json'
$requiredFiles = @('LICENSE', 'NOTICE', 'UPSTREAM.md', 'MODIFICATIONS.md', 'SOURCE_MANIFEST.cmake')

if (-not (Test-Path -LiteralPath $thirdPartyRoot -PathType Container)) {
    throw "Missing third-party registry: $thirdPartyRoot"
}

$components = @(Get-ChildItem -LiteralPath $thirdPartyRoot -Directory | Sort-Object Name)
if ($components.Count -eq 0) {
    throw 'The third-party registry is empty.'
}

foreach ($component in $components) {
    foreach ($required in $requiredFiles) {
        $path = Join-Path $component.FullName $required
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Missing $required for third-party component $($component.Name)"
        }
    }
}

if (-not (Test-Path -LiteralPath $matrixPath -PathType Leaf)) {
    throw "Missing license matrix: $matrixPath"
}
if (-not (Test-Path -LiteralPath $sbomPath -PathType Leaf)) {
    throw "Missing CycloneDX SBOM: $sbomPath"
}

$sbom = Get-Content -LiteralPath $sbomPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($sbom.bomFormat -ne 'CycloneDX' -or $sbom.specVersion -ne '1.5') {
    throw 'SBOM must be CycloneDX 1.5.'
}
if (@($sbom.components).Count -lt $components.Count) {
    throw 'SBOM contains fewer components than the third-party registry.'
}

$manifestPath = Join-Path $RepoRoot 'app\src\main\cpp\wsjtx3\wsjtx3-sources.manifest'
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
$sourceCount = 0
foreach ($line in Get-Content -LiteralPath $manifestPath -Encoding UTF8) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith('#')) { continue }
    $parts = $line.Split('|')
    if ($parts.Count -ne 2 -or -not $seen.Add($parts[1])) {
        throw "Invalid or duplicate WSJT-X source manifest entry: $line"
    }
    $source = Join-Path (Join-Path $RepoRoot 'app\src\main\cpp') $parts[1]
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing WSJT-X manifest source: $($parts[1])"
    }
    $sourceCount++
}

Write-Host ("Third-party compliance: PASS ({0} components, {1} WSJT-X build inputs)" -f $components.Count, $sourceCount)
