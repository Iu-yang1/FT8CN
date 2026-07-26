param(
    [string]$SourceRoot = '',
    [string]$NdkRoot = '',
    [string]$MsysRoot = '',
    [string]$WorkspaceRoot = '',
    [string]$OutputRoot = '',
    [string]$ConfiguredBuildRoot = '',
    [ValidateSet('arm64-v8a')][string]$Abi = 'arm64-v8a',
    [ValidateRange(28, 35)][int]$Api = 28,
    [ValidateRange(1, 64)][int]$Jobs = 8
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
. (Join-Path $PSScriptRoot 'toolchain-common.ps1')

$expectedCommit = 'c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e'
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)
$properties = Get-Ft8cnLocalProperties -RepoRoot $repoRoot

function ConvertTo-MsysPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path).Replace('\', '/')
    if ($fullPath -match '^([A-Za-z]):/(.*)$') {
        return '/' + $matches[1].ToLowerInvariant() + '/' + $matches[2]
    }
    throw "Cannot convert path to MSYS form: $Path"
}

function Invoke-Msys {
    param(
        [Parameter(Mandatory = $true)][string]$BashPath,
        [Parameter(Mandatory = $true)][string]$Command
    )

    & $BashPath -lc $Command
    if ($LASTEXITCODE -ne 0) {
        throw "MSYS command failed with exit code $LASTEXITCODE"
    }
}

if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    $SourceRoot = $env:FT8CN_HAMLIB_SOURCE_ROOT
}
$SourceRoot = Find-Ft8cnDirectory -ExplicitPath $SourceRoot -CandidateRoots $roots `
    -RelativePatterns @(
        'ft8cn-upstream\hamlib-c7fb0fa',
        'hamlib-c7fb0fa',
        'Hamlib'
    ) -RequiredChild 'configure.ac'
if (-not $SourceRoot) {
    throw 'Hamlib source not found. Pass -SourceRoot or set FT8CN_HAMLIB_SOURCE_ROOT.'
}

if ([string]::IsNullOrWhiteSpace($NdkRoot) -and $properties.ContainsKey('ndk.dir')) {
    $NdkRoot = $properties['ndk.dir']
}
if ([string]::IsNullOrWhiteSpace($NdkRoot) -and $properties.ContainsKey('sdk.dir')) {
    $ndkCandidates = @(Get-ChildItem (Join-Path $properties['sdk.dir'] 'ndk') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]$_.Name } -Descending)
    if ($ndkCandidates.Count -gt 0) { $NdkRoot = $ndkCandidates[0].FullName }
}
$NdkRoot = Find-Ft8cnDirectory -ExplicitPath $NdkRoot -CandidateRoots $roots `
    -RelativePatterns @('AndroidSDKLIB\ndk\*', 'ndk\*') -RequiredChild 'source.properties'
if (-not $NdkRoot) {
    throw 'Android NDK not found. Pass -NdkRoot or configure sdk.dir/ANDROID_NDK_HOME.'
}

if ([string]::IsNullOrWhiteSpace($MsysRoot)) {
    $MsysRoot = $env:MSYS2_ROOT
}
$bashPath = Find-Ft8cnExecutable -ExplicitPath $(if ($MsysRoot) { Join-Path $MsysRoot 'usr\bin\bash.exe' } else { '' }) `
    -CommandNames @() -CandidateRoots $roots -RelativePatterns @('msys64\usr\bin\bash.exe', 'usr\bin\bash.exe')
if (-not $bashPath) {
    throw 'MSYS2 bash not found. Pass -MsysRoot or set MSYS2_ROOT.'
}
$MsysRoot = Split-Path -Parent (Split-Path -Parent $bashPath)

$git = Get-Command git.exe, git -ErrorAction Stop | Select-Object -First 1
$sourceCommit = (& $git.Source -C $SourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -ne $expectedCommit) {
    throw "Hamlib source commit mismatch: expected $expectedCommit, found $sourceCommit"
}

$ndkRevision = ((Get-Content (Join-Path $NdkRoot 'source.properties') |
        Select-String '^Pkg.Revision\s*=\s*(.+)$').Matches.Groups[1].Value).Trim()
$scriptHash = Get-Ft8cnFileSha256 -Path $PSCommandPath
$fingerprintInput = @(
    "source=$sourceCommit"
    "ndk=$ndkRevision"
    "abi=$Abi"
    "api=$Api"
    "script=$scriptHash"
) -join "`n"
$fingerprint = Get-Ft8cnStringSha256 -Value $fingerprintInput
$shortFingerprint = $fingerprint.Substring(0, 12)

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $toolsRoot = $roots | Where-Object { Test-Path (Join-Path $_ 'msys64') } | Select-Object -First 1
    if (-not $toolsRoot) { $toolsRoot = Split-Path -Parent $MsysRoot }
    $WorkspaceRoot = Join-Path $toolsRoot "build\hamlib-android-$shortFingerprint"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $WorkspaceRoot 'install'
}

$toolchainBin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$compiler = Join-Path $toolchainBin "aarch64-linux-android$Api-clang.cmd"
if (-not (Test-Path $compiler)) {
    $compiler = Join-Path $toolchainBin "aarch64-linux-android$Api-clang"
}
if (-not (Test-Path $compiler)) {
    throw "NDK compiler not found for API $Api"
}

$sourceCopy = Join-Path $WorkspaceRoot 'source'
$buildRoot = if ($ConfiguredBuildRoot) {
    (Resolve-Path $ConfiguredBuildRoot).ProviderPath
} else {
    Join-Path $WorkspaceRoot 'build'
}
New-Item -ItemType Directory -Path $WorkspaceRoot -Force | Out-Null

if (-not $ConfiguredBuildRoot) {
    if (-not (Test-Path (Join-Path $sourceCopy 'configure.ac'))) {
        New-Item -ItemType Directory -Path $sourceCopy -Force | Out-Null
        Get-ChildItem -LiteralPath $SourceRoot -Force |
            Where-Object Name -ne '.git' |
            Copy-Item -Destination $sourceCopy -Recurse -Force
    }
    $sourceMsys = ConvertTo-MsysPath $sourceCopy
    Invoke-Msys -BashPath $bashPath -Command @"
set -e
cd '$sourceMsys'
find . -type f \( -name '*.ac' -o -name '*.am' -o -name '*.m4' -o -name 'bootstrap' \) -exec sed -i 's/\r$//' {} +
export AUTOCONF=autoconf-2.71 AUTOHEADER=autoheader-2.71 AUTOM4TE=autom4te-2.71
./bootstrap
"@

    New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
    $buildMsys = ConvertTo-MsysPath $buildRoot
    $ndkBinMsys = ConvertTo-MsysPath $toolchainBin
    $sourceCopyMsys = ConvertTo-MsysPath $sourceCopy
    $emptyPkgConfig = Join-Path $WorkspaceRoot 'empty-pkgconfig'
    New-Item -ItemType Directory -Path $emptyPkgConfig -Force | Out-Null
    $emptyPkgMsys = ConvertTo-MsysPath $emptyPkgConfig
    $sysrootMsys = ConvertTo-MsysPath (Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\sysroot')
    Invoke-Msys -BashPath $bashPath -Command @"
set -e
cd '$buildMsys'
export PATH='$ndkBinMsys':`$PATH
export CC='aarch64-linux-android$Api-clang' CXX='aarch64-linux-android$Api-clang++'
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
export PKG_CONFIG=pkgconf PKG_CONFIG_LIBDIR='$emptyPkgMsys' PKG_CONFIG_SYSROOT_DIR='$sysrootMsys'
'$sourceCopyMsys/configure' --host=aarch64-linux-android --prefix=/unused \
  --disable-static --enable-shared --without-libusb --without-readline --without-cxx-binding
"@
}

if (-not (Test-Path (Join-Path $buildRoot 'Makefile'))) {
    throw "Configured Hamlib build root is invalid: $buildRoot"
}
$buildMsys = ConvertTo-MsysPath $buildRoot
$ndkBinMsys = ConvertTo-MsysPath $toolchainBin
Invoke-Msys -BashPath $bashPath -Command "set -e; cd '$buildMsys'; export PATH='$ndkBinMsys':`$PATH; make -j$Jobs"

$builtLibrary = Join-Path $buildRoot 'src\.libs\libhamlib.so'
if (-not (Test-Path $builtLibrary)) {
    throw "Hamlib shared library was not produced: $builtLibrary"
}
$outputLib = Join-Path $OutputRoot 'lib\arm64-v8a'
$outputInclude = Join-Path $OutputRoot 'include\hamlib'
New-Item -ItemType Directory -Path $outputLib, $outputInclude -Force | Out-Null
$installedLibrary = Join-Path $outputLib 'libhamlib.so'
Copy-Item -LiteralPath $builtLibrary -Destination $installedLibrary -Force
& (Join-Path $toolchainBin 'llvm-strip.exe') --strip-unneeded $installedLibrary
if ($LASTEXITCODE -ne 0) { throw 'llvm-strip failed' }
Copy-Item -Path (Join-Path $SourceRoot 'include\hamlib\*.h') -Destination $outputInclude -Force

$readelf = Join-Path $toolchainBin 'llvm-readelf.exe'
$dynamic = (& $readelf -h -d $installedLibrary) -join "`n"
if ($LASTEXITCODE -ne 0 -or $dynamic -notmatch 'Machine:\s+AArch64') {
    throw 'Hamlib output is not an AArch64 ELF library'
}
$needed = [regex]::Matches($dynamic, 'Shared library: \[([^\]]+)\]') |
    ForEach-Object { $_.Groups[1].Value }
$metadata = [ordered]@{
    component = 'Hamlib'
    upstream_commit = $sourceCommit
    ndk_revision = $ndkRevision
    abi = $Abi
    api = $Api
    optimization = 'O2'
    fingerprint = $fingerprint
    library = $installedLibrary
    library_sha256 = Get-Ft8cnFileSha256 -Path $installedLibrary
    library_size_bytes = (Get-Item $installedLibrary).Length
    needed = @($needed)
    generated_utc = [DateTime]::UtcNow.ToString('o')
}
$metadataPath = Join-Path $OutputRoot 'build-metadata.json'
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8
$metadata | ConvertTo-Json -Depth 5
