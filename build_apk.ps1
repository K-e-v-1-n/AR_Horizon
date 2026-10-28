# Builds the native libraries and the debug APK, then copies it to dist/.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot ".")
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME is not valid and no .jdks runtime was found"
}
if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
& (Join-Path $root "android\build_native.ps1")
Push-Location (Join-Path $root "android")
try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$apk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
Copy-Item $apk (Join-Path $dist "AR_Horizon-0.1.0-debug.apk") -Force
Write-Host "APK: $(Join-Path $dist 'AR_Horizon-0.1.0-debug.apk')"
