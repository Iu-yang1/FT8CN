param(
    [string]$OutputDir = '',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$NdkRoot = '',
    [string]$FlangPath = '',
    [string]$BoostHeaders = '',
    [string]$LlvmSourceRoot = '',
    [ValidateSet('Debug', 'Profile', 'Release')]
    [string]$BuildProfile = 'Release',
    [ValidateSet('O2', 'O3')]
    [string]$Optimization = 'O2',
    [string]$TargetTriple = 'aarch64-linux-android21'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ExistingPath([string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
        throw "Missing $Label. Provide the corresponding script parameter or environment variable: $Path"
    }
}

function Get-ObjectPath([string]$Source, [string]$ObjectDir, [string]$CppRoot) {
    $relative = Get-Ft8cnRelativePath -BasePath $CppRoot -Path $Source
    $hash = (Get-Ft8cnStringSha256 ($relative.ToLowerInvariant())).Substring(0, 16)
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($Source)
    return Join-Path $ObjectDir ("$baseName-$hash.o")
}

function Get-UniqueStrings([string[]]$Values) {
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($value in $Values) {
        if ($seen.Add($value)) { $value }
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
$cppRoot = Join-Path $repoRoot 'app\src\main\cpp'
$toolchainCommon = Join-Path $repoRoot 'scripts\toolchain-common.ps1'
. $toolchainCommon
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)

$CMakePath = Find-Ft8cnExecutable -ExplicitPath $CMakePath -CommandNames @('cmake.exe', 'cmake') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\cmake.exe', 'cmake\*\bin\cmake.exe')
$NinjaPath = Find-Ft8cnExecutable -ExplicitPath $NinjaPath -CommandNames @('ninja.exe', 'ninja') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\ninja.exe', 'cmake\*\bin\ninja.exe')
$FlangPath = Find-Ft8cnExecutable -ExplicitPath $FlangPath -CommandNames @('flang.exe', 'flang-new.exe') `
    -CandidateRoots $roots -RelativePatterns @('build\llvm-flang-*\bin\flang.exe', 'llvm*\bin\flang*.exe')
$NdkRoot = Find-Ft8cnDirectory -ExplicitPath $NdkRoot -CandidateRoots $roots `
    -RelativePatterns @('ndk\*', 'AndroidSDKLIB\ndk\*') -RequiredChild 'build\cmake\android.toolchain.cmake'
$BoostHeaders = Find-Ft8cnDirectory -ExplicitPath $BoostHeaders -CandidateRoots $roots `
    -RelativePatterns @('boost_headers', 'boost*') -RequiredChild 'boost\version.hpp'
$LlvmSourceRoot = Find-Ft8cnDirectory -ExplicitPath $LlvmSourceRoot -CandidateRoots $roots `
    -RelativePatterns @('src\llvm-project-*.src', 'llvm-project-*.src') -RequiredChild 'runtimes\CMakeLists.txt'

if (-not $OutputDir) { $OutputDir = Join-Path $scriptDir 'out\arm64-v8a' }
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$manifestPath = Join-Path $cppRoot 'wsjtx3\wsjtx3-sources.manifest'
$runtimeScript = Join-Path $scriptDir 'build_flang_rt_android.ps1'
$runtimePatch = Join-Path $scriptDir 'patches\flang-rt-android-time.patch'
$intrinsicModuleDir = if ($FlangPath) { Join-Path (Split-Path -Parent (Split-Path -Parent $FlangPath)) 'include\flang' } else { '' }
$ndkBin = if ($NdkRoot) { Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin' } else { '' }
$clang = Join-Path $ndkBin 'clang.exe'
$clangxx = Join-Path $ndkBin 'clang++.exe'
$llvmAr = Join-Path $ndkBin 'llvm-ar.exe'

Assert-ExistingPath $CMakePath 'CMake'
Assert-ExistingPath $NinjaPath 'Ninja'
Assert-ExistingPath $NdkRoot 'Android NDK'
Assert-ExistingPath $FlangPath 'Flang'
Assert-ExistingPath $BoostHeaders 'Boost headers'
Assert-ExistingPath $LlvmSourceRoot 'LLVM source root'
Assert-ExistingPath $intrinsicModuleDir 'Flang intrinsic modules'
Assert-ExistingPath $clang 'Android clang'
Assert-ExistingPath $clangxx 'Android clang++'
Assert-ExistingPath $llvmAr 'Android llvm-ar'
Assert-ExistingPath $manifestPath 'WSJT-X source manifest'
Assert-ExistingPath $runtimeScript 'flang-rt build script'
Assert-ExistingPath $runtimePatch 'flang-rt Android patch'

$fortranSources = New-Object System.Collections.Generic.List[string]
$cSources = New-Object System.Collections.Generic.List[string]
$cxxSources = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content $manifestPath -Encoding UTF8) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $fields = $trimmed -split '\|', 2
    if ($fields.Count -ne 2) { throw "Invalid source manifest line: $line" }
    $source = Join-Path $cppRoot $fields[1].Replace('/', '\')
    Assert-ExistingPath $source 'manifest source'
    switch ($fields[0]) {
        'vendor-fortran' { $fortranSources.Add($source) }
        'bridge-fortran' { $fortranSources.Add($source) }
        'vendor-c' { $cSources.Add($source) }
        'bridge-c' { $cSources.Add($source) }
        'android-c' { $cSources.Add($source) }
        'vendor-cxx' { $cxxSources.Add($source) }
        default { throw "Unknown source kind in manifest: $($fields[0])" }
    }
}
$fortranSources = @(Get-UniqueStrings $fortranSources)
$cSources = @(Get-UniqueStrings $cSources)
$cxxSources = @(Get-UniqueStrings $cxxSources)
$allSources = @($fortranSources + $cSources + $cxxSources)

$profileFlags = switch ($BuildProfile) {
    'Debug' { @('-O0', '-g') }
    'Profile' { @('-O2', '-g', '-DNDEBUG') }
    default { @("-$Optimization", '-DNDEBUG') }
}
$fingerprintFiles = @($allSources + @($manifestPath, $runtimeScript, $runtimePatch, $PSCommandPath, $toolchainCommon)) | Sort-Object -Unique
$fingerprintLines = New-Object System.Collections.Generic.List[string]
$fingerprintLines.Add("profile=$BuildProfile")
$fingerprintLines.Add("flags=$($profileFlags -join ' ')")
$fingerprintLines.Add("target=$TargetTriple")
$fingerprintLines.Add('flang=' + (Get-Ft8cnCommandVersion $FlangPath @('--version')))
$fingerprintLines.Add('clang=' + (Get-Ft8cnCommandVersion $clang @('--version')))
$fingerprintLines.Add('ar=' + (Get-Ft8cnCommandVersion $llvmAr @('--version')))
foreach ($file in $fingerprintFiles) {
    $fingerprintLines.Add("file=$(Get-Ft8cnRelativePath $repoRoot $file)|$(Get-Ft8cnFileSha256 $file)")
}
$fingerprint = Get-Ft8cnStringSha256 ($fingerprintLines -join "`n")
$coreArchive = Join-Path $OutputDir 'libwsjtx3_official_core.a'
$runtimeArchive = Join-Path $OutputDir 'libflang_rt.runtime.a'
$fingerprintFile = Join-Path $OutputDir 'wsjtx3-core.fingerprint'

& $runtimeScript -OutputDir $OutputDir -CMakePath $CMakePath `
    -NinjaPath $NinjaPath -NdkRoot $NdkRoot -FlangPath $FlangPath -LlvmSourceRoot $LlvmSourceRoot `
    -BuildProfile $BuildProfile -Optimization $Optimization -TargetTriple $TargetTriple
if (-not $?) { throw 'flang-rt build script failed' }
Assert-ExistingPath $runtimeArchive 'Android flang-rt archive'

if ((Test-Path $coreArchive) -and (Test-Path $fingerprintFile) -and
        ((Get-Content $fingerprintFile -Raw).Trim() -eq $fingerprint)) {
    Write-Host "Official WSJT-X Android core is current: $coreArchive"
    exit 0
}

$workDir = Join-Path $OutputDir (Join-Path 'work' $fingerprint.Substring(0, 16))
$objDir = Join-Path $workDir 'obj'
$modDir = Join-Path $workDir 'mod'
$probeDir = Join-Path $workDir 'probe'
$logDir = Join-Path $workDir 'logs'
New-Item -ItemType Directory -Force -Path $OutputDir, $objDir, $modDir, $probeDir, $logDir | Out-Null
$compileLog = Join-Path $logDir 'compile.log'

$fortranIncludeDirs = @(
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\77bit'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\ft8'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\ft4'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\ft8var'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\qra\q65'),
    (Join-Path $cppRoot 'wsjtx3')
)
$nativeIncludeDirs = @(
    $cppRoot,
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib'),
    (Join-Path $cppRoot 'wsjtx3\vendor\wsjtx-3.0.0\lib\qra\q65'),
    (Join-Path $cppRoot 'wsjtx3\host'),
    $BoostHeaders
)

$pending = New-Object System.Collections.Generic.List[string]
$fortranSources | ForEach-Object { $pending.Add($_) }
$objects = New-Object System.Collections.Generic.List[string]
$pass = 0
while ($pending.Count -gt 0) {
    $pass++
    $progress = 0
    $next = New-Object System.Collections.Generic.List[string]
    Add-Content -LiteralPath $compileLog -Value "PASS $pass pending=$($pending.Count)" -Encoding UTF8
    foreach ($source in $pending) {
        $object = Get-ObjectPath $source $objDir $cppRoot
        $arguments = @('-target', $TargetTriple, '-fPIC') + $profileFlags + @(
            '-fintrinsic-modules-path', $intrinsicModuleDir,
            '-module-dir', $modDir
        )
        foreach ($include in $fortranIncludeDirs) { $arguments += @('-I', $include) }
        $arguments += @('-c', $source, '-o', $object)
        $result = Invoke-Ft8cnNativeCapture -Path $FlangPath -Arguments $arguments
        if ($result.ExitCode -eq 0) {
            $objects.Add($object)
            $progress++
        } else {
            $next.Add($source)
            Add-Content -LiteralPath $compileLog -Value ("WAIT $source`n$($result.Output)") -Encoding UTF8
        }
    }
    if ($progress -eq 0) {
        throw "Official WSJT-X Fortran core made no compile progress. See $compileLog"
    }
    $pending = $next
}

foreach ($source in $cxxSources) {
    $object = Get-ObjectPath $source $objDir $cppRoot
    $arguments = @('-target', $TargetTriple, '-fPIC', '-std=c++17') + $profileFlags
    foreach ($include in $nativeIncludeDirs) { $arguments += @('-I', $include) }
    $arguments += @('-c', $source, '-o', $object)
    $result = Invoke-Ft8cnNativeCapture -Path $clangxx -Arguments $arguments
    if ($result.ExitCode -ne 0) { throw "Official C++ helper compile failed: $source`n$($result.Output)" }
    $objects.Add($object)
}
foreach ($source in $cSources) {
    $object = Get-ObjectPath $source $objDir $cppRoot
    $arguments = @('-target', $TargetTriple, '-fPIC', '-std=c11') + $profileFlags
    if ($source -notmatch '\\vendor\\') { $arguments += @('-Wall', '-Wextra', '-Werror') }
    foreach ($include in $nativeIncludeDirs) { $arguments += @('-I', $include) }
    $arguments += @('-c', $source, '-o', $object)
    $result = Invoke-Ft8cnNativeCapture -Path $clang -Arguments $arguments
    if ($result.ExitCode -ne 0) { throw "Official C helper compile failed: $source`n$($result.Output)" }
    $objects.Add($object)
}

$candidateArchive = Join-Path $workDir 'libwsjtx3_official_core.candidate.a'
if (Test-Path -LiteralPath $candidateArchive) {
    Remove-Item -LiteralPath $candidateArchive -Force
}
$archiveResult = Invoke-Ft8cnNativeCapture -Path $llvmAr -Arguments (@('rcs', $candidateArchive) + @($objects))
if ($archiveResult.ExitCode -ne 0) { throw "Official core archive creation failed:`n$($archiveResult.Output)" }

$probeSource = Join-Path $probeDir 'android_link_probe.c'
$probeObject = Join-Path $probeDir 'android_link_probe.o'
$probeLibrary = Join-Path $probeDir 'libandroid_wsjtx3_probe.so'
@'
#include "app/src/main/cpp/wsjtx3/wsjtx3_bridge.h"

int wsjtx3_android_probe(const float *samples, int count) {
    wsjtx3_bridge_decode_result_t result;
    int created;
    int processed;
    int available;
    int got_result = 1;

    if (samples == 0 || count <= 0) return -1;
    created = wsjtx3_bridge_create(0, 12000, count, 0);
    if (created <= 0) return -2;
    wsjtx3_bridge_set_options(created, 1, 1, 1, 1, 0, 0, 20);
    wsjtx3_bridge_set_qso_frequencies(created, 1000, 1000);
    processed = wsjtx3_bridge_process_float(created, samples, count);
    if (processed < 0) {
        wsjtx3_bridge_destroy(created);
        return -3;
    }
    available = wsjtx3_bridge_get_result_count(created);
    if (available != processed) {
        wsjtx3_bridge_destroy(created);
        return -4;
    }
    if (available > 0) got_result = wsjtx3_bridge_get_result(created, 0, &result);
    wsjtx3_bridge_destroy(created);
    return got_result ? available : -5;
}
'@ | Set-Content -LiteralPath $probeSource -Encoding ASCII

$probeCompile = Invoke-Ft8cnNativeCapture -Path $clang -Arguments @(
    '-target', $TargetTriple, '-fPIC', '-std=c11', '-Wall', '-Wextra', '-Werror',
    '-I', $repoRoot, '-c', $probeSource, '-o', $probeObject
)
if ($probeCompile.ExitCode -ne 0) { throw "Android link probe compile failed:`n$($probeCompile.Output)" }
$linkArguments = @(
    '-target', $TargetTriple, '-shared', '-fPIC', '-Wl,-soname,libandroid_wsjtx3_probe.so',
    '-Wl,--no-undefined', '-o', $probeLibrary, $probeObject,
    '-Wl,--whole-archive', $candidateArchive, '-Wl,--no-whole-archive',
    $runtimeArchive, '-lm', '-lc', '-ldl'
)
$link = Invoke-Ft8cnNativeCapture -Path $clangxx -Arguments $linkArguments
if ($link.ExitCode -ne 0) { throw "Official WSJT-X Android core link validation failed:`n$($link.Output)" }

Move-Item -LiteralPath $candidateArchive -Destination $coreArchive -Force
Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -Encoding ASCII
Write-Host "Official WSJT-X Android core ready: $coreArchive"
Write-Host "Official flang runtime ready: $runtimeArchive"
