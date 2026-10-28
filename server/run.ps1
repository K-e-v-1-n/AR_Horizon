$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$python = Join-Path $root ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) { throw "create .venv and install tools/requirements.txt first" }
if (-not (Test-Path (Join-Path $root "server\data\packs\alps-matterhorn.sqlite"))) {
    & (Join-Path $root "server\prepare.ps1")
}
Push-Location (Join-Path $root "server")
try {
    & $python -m uvicorn app:app --host 0.0.0.0 --port 8000
} finally {
    Pop-Location
}
