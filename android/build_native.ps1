# Builds the JNI shared library with the project-local NDK (no CMake needed).
param(
    [string]$Ndk = (Join-Path $PSScriptRoot "..\.tools\android-ndk-r27c")
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$bin = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $bin)) { throw "NDK not found at $Ndk" }

$src = @(
    (Join-Path $root "core\src\core.cpp"),
    (Join-Path $root "core\jni\vantage_jni.cpp")
)
$include = Join-Path $root "core\include"
$internal = Join-Path $root "core\src"
$out = Join-Path $PSScriptRoot "app\src\main\jniLibs"

$targets = @(
    @{ Cmd = "aarch64-linux-android28-clang++.cmd"; Dir = "arm64-v8a" },
    @{ Cmd = "armv7a-linux-androideabi28-clang++.cmd"; Dir = "armeabi-v7a" },
    @{ Cmd = "x86_64-linux-android28-clang++.cmd"; Dir = "x86_64" }
)

foreach ($t in $targets) {
    $dir = Join-Path $out $t.Dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $lib = Join-Path $dir "libvantage_core.so"
    Write-Host "building $($t.Dir)"
    $clangArgs = @(
        "-shared", "-fPIC", "-O3", "-std=c++17", "-Wall", "-Wextra",
        "-I", $include, "-I", $internal, "-static-libstdc++",
        "-Wl,--gc-sections", "-Wl,-z,max-page-size=16384",
        "-o", $lib
    ) + $src + @("-llog")
    & (Join-Path $bin $t.Cmd) @clangArgs
    if ($LASTEXITCODE -ne 0) { throw "native build failed for $($t.Dir)" }
    $size = (Get-Item $lib).Length / 1KB
    Write-Host ("  {0}  {1:N0} KB" -f $lib, $size)
}
Write-Host "native libraries ready"
