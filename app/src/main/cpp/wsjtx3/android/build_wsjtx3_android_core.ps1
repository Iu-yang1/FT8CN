param(
    [string] $OutputDir = '',
    [string] $CMakePath = 'H:\tools\msys64\ucrt64\bin\cmake.exe',
    [string] $NinjaPath = 'H:\tools\msys64\ucrt64\bin\ninja.exe',
    [string] $NdkRoot = 'H:\iu_yang1\AndroidSDKLIB\ndk\23.1.7779620',
    [string] $FlangPath = 'H:\tools\build\llvm-flang-22.1.5-clangcl\bin\flang.exe',
    [string] $BoostHeaders = 'H:\tools\boost_headers',
    [string] $TargetTriple = 'aarch64-linux-android21'
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Assert-Path([string] $path, [string] $label) {
    if (-not (Test-Path $path)) {
        throw "Missing ${label}: $path"
    }
}

function Invoke-NativeCapture([string] $command, [string[]] $arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $command @arguments 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) {
                $_.ToString()
            } else {
                [string] $_
            }
        } | Out-String
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output = $output.TrimEnd()
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Write-LogLine([string] $path, [string] $line) {
    Add-Content -Path $path -Value $line -Encoding UTF8
}

function Get-UniquePaths([System.Collections.Generic.List[string]] $paths) {
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $unique = New-Object System.Collections.Generic.List[string]
    foreach ($path in $paths) {
        if ($seen.Add($path)) {
            $unique.Add($path)
        }
    }
    return $unique
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
$hostCMakePath = Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\CMakeLists.txt'
$runtimeScript = Join-Path $scriptDir 'build_flang_rt_android.ps1'
$intrinsicModuleDir = Join-Path (Split-Path -Parent (Split-Path -Parent $FlangPath)) 'include\flang'
$ndkBin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$clang = Join-Path $ndkBin 'clang.exe'
$clangxx = Join-Path $ndkBin 'clang++.exe'
$llvmAr = Join-Path $ndkBin 'llvm-ar.exe'
$openMpArchive = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\lib64\clang\12.0.8\lib\linux\aarch64\libomp.a'

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $scriptDir 'out\arm64-v8a'
}

$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$runtimeBuildDir = Join-Path $OutputDir 'flang_rt_build'
$objDir = Join-Path $OutputDir 'obj'
$modDir = Join-Path $OutputDir 'mod'
$logDir = Join-Path $OutputDir 'logs'
$probeDir = Join-Path $OutputDir 'probe'
$runtimeArchive = Join-Path $OutputDir 'libflang_rt.runtime.a'
$coreArchive = Join-Path $OutputDir 'libwsjtx3_official_core.a'
$compileLog = Join-Path $logDir 'compile.log'
$linkLog = Join-Path $logDir 'link.log'

Assert-Path $repoRoot 'FT8CN repo root'
Assert-Path $hostCMakePath 'WSJT-X host whitelist'
Assert-Path $runtimeScript 'flang-rt build script'
Assert-Path $FlangPath 'Flang'
Assert-Path $intrinsicModuleDir 'Flang intrinsic modules'
Assert-Path $NdkRoot 'Android NDK'
Assert-Path $BoostHeaders 'Boost headers'
Assert-Path $clang 'Android clang'
Assert-Path $clangxx 'Android clang++'
Assert-Path $llvmAr 'Android llvm-ar'
Assert-Path $openMpArchive 'Android OpenMP runtime'

New-Item -ItemType Directory -Force -Path $OutputDir, $runtimeBuildDir, $objDir, $modDir, $logDir, $probeDir | Out-Null
Remove-Item $compileLog, $linkLog -ErrorAction SilentlyContinue

& $runtimeScript -OutputDir $OutputDir -BuildDir $runtimeBuildDir -CMakePath $CMakePath -NinjaPath $NinjaPath -NdkRoot $NdkRoot -FlangPath $FlangPath -TargetTriple $TargetTriple
if ($LASTEXITCODE -ne 0) {
    throw 'flang-rt build script failed'
}
Assert-Path $runtimeArchive 'Android flang-rt archive'

$vendorFortranSources = New-Object System.Collections.Generic.List[string]
$vendorCppSources = New-Object System.Collections.Generic.List[string]
$vendorCSources = New-Object System.Collections.Generic.List[string]
$hostSupportFortranSources = @(
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\wsjtx3_bridge.f90'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\wsjtx3_openmp_probe.f90')
)
$hostSupportCSources = @(
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\shmem_stub.c'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host\wsjtx3_phase_trace.c'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\android\fftw3f_kiss_shim.c'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\android\complex_math_shim.c'),
    (Join-Path $repoRoot 'app\src\main\cpp\fft\kiss_fft.c'),
    (Join-Path $repoRoot 'app\src\main\cpp\fft\kiss_fftr.c')
)

foreach ($line in Get-Content $hostCMakePath) {
    $trimmedLine = $line.Trim()
    if ($trimmedLine -match '^\$\{WSJTX3_VENDOR_LIB\}/(.+?\.f90)$') {
        $vendorFortranSources.Add((Join-Path $repoRoot ('app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\' + $matches[1].Replace('/', '\'))))
    }
    if ($trimmedLine -match '^\$\{WSJTX3_VENDOR_LIB\}/(.+?\.cpp)$') {
        $vendorCppSources.Add((Join-Path $repoRoot ('app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\' + $matches[1].Replace('/', '\'))))
    }
    if ($trimmedLine -match '^\$\{WSJTX3_VENDOR_LIB\}/(.+?\.c)$') {
        $vendorCSources.Add((Join-Path $repoRoot ('app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\' + $matches[1].Replace('/', '\'))))
    }
}

$vendorFortranSources = Get-UniquePaths $vendorFortranSources
$vendorCppSources = Get-UniquePaths $vendorCppSources
$vendorCSources = Get-UniquePaths $vendorCSources

$fortranSources = New-Object System.Collections.Generic.List[string]
$vendorFortranSources | ForEach-Object { $fortranSources.Add($_) }
$hostSupportFortranSources | ForEach-Object { $fortranSources.Add($_) }
$fortranSources = Get-UniquePaths $fortranSources

$fortranIncludeDirs = @(
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\77bit'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\ft8'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\ft4'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\ft8var'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\qra\q65'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3')
)
$nativeIncludeDirs = @(
    (Join-Path $repoRoot 'app\src\main\cpp'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\vendor\wsjtx-3.0.0\lib\qra\q65'),
    (Join-Path $repoRoot 'app\src\main\cpp\wsjtx3\host'),
    $BoostHeaders
)

$pending = New-Object System.Collections.Generic.List[string]
$fortranSources | ForEach-Object { $pending.Add($_) }
$compiledObjects = New-Object System.Collections.Generic.List[string]
$pass = 0

while ($pending.Count -gt 0) {
    $pass++
    $progress = 0
    $next = New-Object System.Collections.Generic.List[string]
    Write-LogLine $compileLog "PASS $pass pending=$($pending.Count)"

    foreach ($src in $pending) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($src)
        $objPath = Join-Path $objDir ($name + '.o')
        $cmdArgs = @(
            '-target', $TargetTriple,
            '-fPIC',
            '-fintrinsic-modules-path', $intrinsicModuleDir,
            '-module-dir', $modDir
        )
        foreach ($includeDir in $fortranIncludeDirs) {
            $cmdArgs += @('-I', $includeDir)
        }
        if ([System.IO.Path]::GetFileName($src) -eq 'wsjtx3_openmp_probe.f90') {
            $cmdArgs += '-fopenmp'
        }
        $cmdArgs += @('-c', $src, '-o', $objPath)
        $result = Invoke-NativeCapture -command $FlangPath -arguments $cmdArgs

        if ($result.ExitCode -eq 0) {
            $compiledObjects.Add($objPath)
            $progress++
            Write-LogLine $compileLog ('  OK  ' + $src)
        } else {
            $next.Add($src)
            Write-LogLine $compileLog ('  WAIT ' + $src)
            if ($result.Output) {
                Write-LogLine $compileLog $result.Output
            }
        }
    }

    if ($progress -eq 0) {
        Get-Content $compileLog | Write-Host
        throw 'Official WSJT-X Fortran core made no compile progress'
    }
    $pending = $next
}

foreach ($src in $vendorCppSources) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($src)
    $objPath = Join-Path $objDir ($name + '.obj')
    $cmdArgs = @(
        '-target', $TargetTriple,
        '-fPIC',
        '-c',
        '-std=c++17'
    )
    foreach ($includeDir in $nativeIncludeDirs) {
        $cmdArgs += @('-I', $includeDir)
    }
    $cmdArgs += @($src, '-o', $objPath)
    $result = Invoke-NativeCapture -command $clangxx -arguments $cmdArgs
    if ($result.ExitCode -ne 0) {
        if ($result.Output) {
            Write-Host $result.Output
        }
        throw "Official C++ helper compile failed: $src"
    }
    $compiledObjects.Add($objPath)
}

foreach ($src in $vendorCSources) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($src)
    $objPath = Join-Path $objDir ($name + '.obj')
    $cmdArgs = @(
        '-target', $TargetTriple,
        '-fPIC',
        '-c',
        '-std=c11'
    )
    foreach ($includeDir in $nativeIncludeDirs) {
        $cmdArgs += @('-I', $includeDir)
    }
    $cmdArgs += @($src, '-o', $objPath)
    $result = Invoke-NativeCapture -command $clang -arguments $cmdArgs
    if ($result.ExitCode -ne 0) {
        if ($result.Output) {
            Write-Host $result.Output
        }
        throw "Official C helper compile failed: $src"
    }
    $compiledObjects.Add($objPath)
}

foreach ($src in $hostSupportCSources) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($src)
    $objPath = Join-Path $objDir ($name + '.obj')
    $cmdArgs = @(
        '-target', $TargetTriple,
        '-fPIC',
        '-c',
        '-std=c11'
    )
    foreach ($includeDir in $nativeIncludeDirs) {
        $cmdArgs += @('-I', $includeDir)
    }
    $cmdArgs += @($src, '-o', $objPath)
    $result = Invoke-NativeCapture -command $clang -arguments $cmdArgs
    if ($result.ExitCode -ne 0) {
        if ($result.Output) {
            Write-Host $result.Output
        }
        throw "Official C helper compile failed: $src"
    }
    $compiledObjects.Add($objPath)
}

Remove-Item $coreArchive -ErrorAction SilentlyContinue
$compiledObjects = Get-UniquePaths $compiledObjects
& $llvmAr rcs $coreArchive @($compiledObjects)
if ($LASTEXITCODE -ne 0) {
    throw "Official WSJT-X archive creation failed: $coreArchive"
}

$probeSource = Join-Path $probeDir 'android_link_probe.c'
$probeObj = Join-Path $probeDir 'android_link_probe.obj'
$probeSo = Join-Path $probeDir 'libandroid_wsjtx3_probe.so'

@'
#include "app/src/main/cpp/wsjtx3/wsjtx3_bridge.h"
int wsjtx3_android_probe(int handle, const float *samples, int count) {
    wsjtx3_bridge_decode_result_t result;
    int created = wsjtx3_bridge_create(0, 12000, count, 0);
    wsjtx3_bridge_process_float(handle, samples, count);
    wsjtx3_bridge_get_result(created, 0, &result);
    wsjtx3_bridge_destroy(created);
    return result.snr;
}
'@ | Set-Content -Path $probeSource -Encoding ASCII

$probeCompileCommands = @(
    [pscustomobject]@{
        Command = $clang
        Arguments = @(
        '-target', $TargetTriple,
        '-fPIC',
        '-c',
        '-std=c11',
        '-I', $repoRoot,
        $probeSource,
        '-o', $probeObj
    )
    }
)

foreach ($command in $probeCompileCommands) {
    $result = Invoke-NativeCapture -command $command.Command -arguments $command.Arguments
    if ($result.ExitCode -ne 0) {
        if ($result.Output) {
            Write-Host $result.Output
        }
        throw 'Android link probe compile failed'
    }
}

$linkArgs = @(
    '-target', $TargetTriple,
    '-shared',
    '-fPIC',
    '-Wl,-soname,libandroid_wsjtx3_probe.so',
    '-o', $probeSo,
    '-Wl,--no-undefined',
    $probeObj,
    '-Wl,--whole-archive',
    $coreArchive,
    '-Wl,--no-whole-archive',
    $runtimeArchive,
    $openMpArchive,
    '-lm',
    '-lc',
    '-ldl'
)
$linkResult = Invoke-NativeCapture -command $clangxx -arguments $linkArgs
if ($linkResult.ExitCode -ne 0) {
    if ($linkResult.Output) {
        Set-Content -Path $linkLog -Value $linkResult.Output -Encoding UTF8
        Get-Content $linkLog | Write-Host
    }
    throw 'Official WSJT-X Android core link validation failed'
}

Write-Host "Official WSJT-X Android core ready: $coreArchive"
Write-Host "Official flang runtime ready: $runtimeArchive"
