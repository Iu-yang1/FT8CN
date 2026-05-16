param(
    [string] $OutputDir = '',
    [string] $BuildDir = '',
    [string] $CMakePath = 'H:\tools\msys64\ucrt64\bin\cmake.exe',
    [string] $NinjaPath = 'H:\tools\msys64\ucrt64\bin\ninja.exe',
    [string] $NdkRoot = 'H:\iu_yang1\AndroidSDKLIB\ndk\23.1.7779620',
    [string] $FlangPath = 'H:\tools\build\llvm-flang-22.1.5-clangcl\bin\flang.exe',
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

function Ensure-AndroidTimePatch([string] $filePath) {
    $content = Get-Content $filePath -Raw
    if ($content.Contains('clock_gettime(CLOCK_REALTIME, &tspec)')) {
        return
    }

    $needle = "  if (timespec_get(&tspec, TIME_UTC) < 0) {"
    $replacement = @"
#if defined(__ANDROID__)
  if (clock_gettime(CLOCK_REALTIME, &tspec) != 0) {
#else
  if (timespec_get(&tspec, TIME_UTC) < 0) {
#endif
"@
    if (-not $content.Contains($needle)) {
        throw "Patch anchor for timespec_get was not found: $filePath"
    }

    $patched = $content.Replace($needle, $replacement.TrimEnd("`r", "`n"))
    Set-Content -Path $filePath -Value $patched -Encoding UTF8
}

function Invoke-CmdLine([string] $commandLine, [string] $logPath) {
    & cmd /c "$commandLine > `"$logPath`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Get-Content $logPath | Write-Host
        throw "Command failed, see log: $logPath"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$llvmRoot = 'H:\tools\src\llvm-project-22.1.5.src'
$runtimeSource = Join-Path $llvmRoot 'runtimes'
$runtimePatchFile = Join-Path $llvmRoot 'flang-rt\lib\runtime\time-intrinsic.cpp'
$toolchainFile = Join-Path $NdkRoot 'build\cmake\android.toolchain.cmake'

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $scriptDir 'out\arm64-v8a'
}
if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    $BuildDir = Join-Path $OutputDir 'flang_rt_build'
}

$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$BuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$runtimeArchive = Join-Path $OutputDir 'libflang_rt.runtime.a'
$configureLog = Join-Path $BuildDir 'configure.log'
$buildLog = Join-Path $BuildDir 'build.log'

Assert-Path $CMakePath 'CMake'
Assert-Path $NinjaPath 'Ninja'
Assert-Path $NdkRoot 'Android NDK'
Assert-Path $FlangPath 'Flang'
Assert-Path $runtimeSource 'LLVM runtimes source'
Assert-Path $runtimePatchFile 'Flang runtime time source'
Assert-Path $toolchainFile 'Android toolchain'

Ensure-AndroidTimePatch -filePath $runtimePatchFile

New-Item -ItemType Directory -Force -Path $OutputDir, $BuildDir | Out-Null

$configureCmd = @(
    '"' + $CMakePath + '"',
    '-G Ninja',
    '-S "' + $runtimeSource + '"',
    '-B "' + $BuildDir + '"',
    '-DCMAKE_MAKE_PROGRAM="' + $NinjaPath + '"',
    '-DLLVM_ENABLE_RUNTIMES=flang-rt',
    '-DLLVM_DEFAULT_TARGET_TRIPLE=' + $TargetTriple,
    '-DCMAKE_TOOLCHAIN_FILE="' + $toolchainFile + '"',
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=21',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DCMAKE_C_COMPILER_TARGET=' + $TargetTriple,
    '-DCMAKE_CXX_COMPILER_TARGET=' + $TargetTriple,
    '-DCMAKE_Fortran_COMPILER="' + $FlangPath + '"',
    '-DCMAKE_Fortran_COMPILER_TARGET=' + $TargetTriple,
    '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
    '-DCMAKE_Fortran_COMPILER_WORKS=ON',
    '-DCMAKE_Fortran_COMPILER_FORCED=ON',
    '-DFLANG_RT_INCLUDE_TESTS=OFF',
    '-DFLANG_RT_ENABLE_SHARED=OFF',
    '-DFLANG_RT_ENABLE_STATIC=ON'
) -join ' '
Invoke-CmdLine -commandLine $configureCmd -logPath $configureLog

& $CMakePath --build $BuildDir --target flang_rt.runtime.static --config Release 2>&1 | Tee-Object -FilePath $buildLog | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "flang-rt build failed, see: $buildLog"
}

$builtRuntimeArchive = Join-Path $BuildDir 'flang-rt\lib\libflang_rt.runtime.a'
Assert-Path $builtRuntimeArchive 'flang-rt static library'
Copy-Item $builtRuntimeArchive $runtimeArchive -Force

Write-Host "flang-rt Android archive ready: $runtimeArchive"
