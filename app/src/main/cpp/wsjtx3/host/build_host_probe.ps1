$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$buildDir = Join-Path $PSScriptRoot "build"
$ucrtBin = "H:\tools\msys64\ucrt64\bin"
$msysBin = "H:\tools\msys64\usr\bin"

if (-not (Test-Path $ucrtBin)) {
    throw "未找到 ucrt64 工具链目录: $ucrtBin"
}

$env:PATH = "$ucrtBin;$msysBin;$env:PATH"

& "$ucrtBin\cmake.exe" -S $PSScriptRoot -B $buildDir -G Ninja
& "$ucrtBin\cmake.exe" --build $buildDir
