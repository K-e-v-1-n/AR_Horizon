$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$src = Join-Path $root "android\app\src\main\assets\packs\alps.pack.sqlite"
$dir = Join-Path $root "server\data\packs"
$dst = Join-Path $dir "alps-matterhorn.sqlite"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if (-not (Test-Path $src)) { throw "bundled pack missing; run tools/build_pack.py first" }
Copy-Item $src $dst -Force
Write-Host "catalogue pack ready: $dst"
