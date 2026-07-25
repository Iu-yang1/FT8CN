param(
    [string]$JavaHome = '',
    [string]$AndroidSdkRoot = '',
    [string]$NdkRoot = '',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$ClangPath = '',
    [string]$FlangPath = '',
    [string]$AdbPath = '',
    [string]$BoostHeaders = '',
    [switch]$PassThru
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')

$properties = Get-Ft8cnLocalProperties -RepoRoot $repoRoot
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot) -and $properties.ContainsKey('sdk.dir')) {
    $AndroidSdkRoot = $properties['sdk.dir']
}

$explicitJavac = ''
if ($JavaHome) {
    $explicitJavac = Join-Path $JavaHome 'bin\javac.exe'
}
$javacCandidates = New-Object System.Collections.Generic.List[string]
if ($explicitJavac -and (Test-Path $explicitJavac)) { $javacCandidates.Add($explicitJavac) }
foreach ($root in $roots) {
    foreach ($pattern in @('jdks\jdk-17*\bin\javac.exe', 'jdk*17*\bin\javac.exe',
            'jdk17\bin\javac.exe', 'bin\javac.exe')) {
        foreach ($candidate in @(Get-ChildItem (Join-Path $root $pattern) -File -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending)) {
            $javacCandidates.Add($candidate.FullName)
        }
    }
}
$commandJavac = Get-Command javac.exe, javac -ErrorAction SilentlyContinue | Select-Object -First 1
if ($commandJavac -and (Test-Path $commandJavac.Source)) { $javacCandidates.Add($commandJavac.Source) }
foreach ($candidate in $javacCandidates) {
    $version = Get-Ft8cnCommandVersion $candidate @('-version')
    if ($version -match '^javac\s+17(\.|$)') {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $candidate)
        break
    }
}

$AndroidSdkRoot = Find-Ft8cnDirectory -ExplicitPath $AndroidSdkRoot -CandidateRoots $roots `
    -RelativePatterns @('AndroidSDKLIB', 'Android\Sdk') -RequiredChild 'platform-tools\adb.exe'
if ($AndroidSdkRoot) {
    if (-not $AdbPath) { $AdbPath = Join-Path $AndroidSdkRoot 'platform-tools\adb.exe' }
    if (-not $NdkRoot) {
        $ndkCandidates = @(Get-ChildItem (Join-Path $AndroidSdkRoot 'ndk') -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version]$_.Name } -Descending)
        if ($ndkCandidates.Count -gt 0) { $NdkRoot = $ndkCandidates[0].FullName }
    }
    if (-not $CMakePath) {
        $cmakeCandidates = @(Get-ChildItem (Join-Path $AndroidSdkRoot 'cmake') -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version]$_.Name } -Descending)
        if ($cmakeCandidates.Count -gt 0) {
            $CMakePath = Join-Path $cmakeCandidates[0].FullName 'bin\cmake.exe'
            $NinjaPath = Join-Path $cmakeCandidates[0].FullName 'bin\ninja.exe'
        }
    }
}

$CMakePath = Find-Ft8cnExecutable -ExplicitPath $CMakePath -CommandNames @('cmake.exe', 'cmake') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\cmake.exe', 'cmake\*\bin\cmake.exe')
$NinjaPath = Find-Ft8cnExecutable -ExplicitPath $NinjaPath -CommandNames @('ninja.exe', 'ninja') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\ninja.exe', 'cmake\*\bin\ninja.exe')
$FlangPath = Find-Ft8cnExecutable -ExplicitPath $FlangPath -CommandNames @('flang.exe', 'flang-new.exe', 'flang') `
    -CandidateRoots $roots -RelativePatterns @('build\llvm-flang-*\bin\flang.exe', 'llvm*\bin\flang*.exe')
$AdbPath = Find-Ft8cnExecutable -ExplicitPath $AdbPath -CommandNames @('adb.exe', 'adb') `
    -CandidateRoots $roots -RelativePatterns @('platform-tools\adb.exe', 'AndroidSDKLIB\platform-tools\adb.exe')
$BoostHeaders = Find-Ft8cnDirectory -ExplicitPath $BoostHeaders -CandidateRoots $roots `
    -RelativePatterns @('boost_headers', 'boost*') -RequiredChild 'boost\version.hpp'
if (-not $ClangPath -and $NdkRoot) {
    $ClangPath = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
}

$tools = [ordered]@{
    RepoRoot = $repoRoot
    JavaHome = $JavaHome
    GradleWrapper = Join-Path $repoRoot 'gradlew.bat'
    AndroidSdkRoot = $AndroidSdkRoot
    NdkRoot = $NdkRoot
    CMake = $CMakePath
    Ninja = $NinjaPath
    Clang = $ClangPath
    Flang = $FlangPath
    Adb = $AdbPath
    BoostHeaders = $BoostHeaders
}

$missing = New-Object System.Collections.Generic.List[string]
foreach ($entry in $tools.GetEnumerator()) {
    if ($entry.Key -eq 'RepoRoot') { continue }
    if ([string]::IsNullOrWhiteSpace([string]$entry.Value) -or -not (Test-Path $entry.Value)) {
        $missing.Add($entry.Key)
        Write-Host ("[MISSING] {0}: provide an explicit parameter, environment variable, local.properties, or install it under a discovered tools root" -f $entry.Key)
    } else {
        Write-Host ("[OK] {0}={1}" -f $entry.Key, $entry.Value)
    }
}

if ($missing.Count -eq 0) {
    Write-Host ('[VERSION] Java: ' + (Get-Ft8cnCommandVersion (Join-Path $JavaHome 'bin\java.exe') @('-version')).Split("`n")[0])
    Write-Host ('[VERSION] CMake: ' + (Get-Ft8cnCommandVersion $CMakePath @('--version')).Split("`n")[0])
    Write-Host ('[VERSION] Ninja: ' + (Get-Ft8cnCommandVersion $NinjaPath @('--version')).Split("`n")[0])
    Write-Host ('[VERSION] Clang: ' + (Get-Ft8cnCommandVersion $ClangPath @('--version')).Split("`n")[0])
    Write-Host ('[VERSION] Flang: ' + (Get-Ft8cnCommandVersion $FlangPath @('--version')).Split("`n")[0])
    Write-Host ('[VERSION] ADB: ' + (Get-Ft8cnCommandVersion $AdbPath @('version')).Split("`n")[0])
}

if ($PassThru) {
    [pscustomobject]$tools
}
if ($missing.Count -gt 0) {
    exit 1
}
