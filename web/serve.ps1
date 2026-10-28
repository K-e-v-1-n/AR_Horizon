$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$python = Join-Path $root ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    $python = "python"
}
Push-Location $PSScriptRoot
try {
    Write-Host "Vantage desktop simulator: http://127.0.0.1:8099"
    & $python -m http.server 8099 --bind 127.0.0.1
} finally {
    Pop-Location
}
