param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$sourceExtensions = @(
    ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp",
    ".f", ".f90", ".java", ".kt", ".kts", ".xml",
    ".gradle", ".cmake", ".ps1", ".json", ".md",
    ".properties", ".pro"
)

Push-Location $RepositoryRoot
try {
    $paths = @(
        git -c core.quotepath=false ls-files --cached --others --exclude-standard
    ) | Where-Object {
        $extension = [System.IO.Path]::GetExtension($_).ToLowerInvariant()
        $sourceExtensions -contains $extension
    } | Sort-Object -Unique

    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($relativePath in $paths) {
        $absolutePath = Join-Path $RepositoryRoot $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            continue
        }

        try {
            $text = [System.IO.File]::ReadAllText($absolutePath, $utf8Strict)
        } catch {
            $failures.Add("INVALID_UTF8 $relativePath")
            continue
        }

        if ($text.Contains([char]0xFFFD)) {
            $failures.Add("REPLACEMENT_CHARACTER $relativePath")
        }
        if ($text -match '[\uE000-\uF8FF]') {
            $failures.Add("PRIVATE_USE_CHARACTER $relativePath")
        }
        if ($text -match '\u951F\u65A4\u62F7|\u00C3[\u0080-\u00FF]?|\u00C2[\u0080-\u00FF]?|\u00E2[\u0080-\u00FF]{1,3}|\u00F0\u0178') {
            $failures.Add("LIKELY_MOJIBAKE $relativePath")
        }
    }

    if ($failures.Count -gt 0) {
        $failures | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
        exit 1
    }

    Write-Host "SOURCE_ENCODING_PASS files=$($paths.Count) encoding=UTF-8"
} finally {
    Pop-Location
}
