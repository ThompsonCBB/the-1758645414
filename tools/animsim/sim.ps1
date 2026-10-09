# Runs the animation simulator: compiles the mod's pose code + SimMain, dumps frames, renders them.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File tools\animsim\sim.ps1 [hide|look]
param([string]$Scenario = 'hide')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$build = Join-Path $here 'build'
$out = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $build, $out | Out-Null

javac -encoding UTF-8 -d $build (Join-Path $root 'src\main\java\com\anta\anim\WatcherPose.java') (Join-Path $here 'SimMain.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$json = Join-Path $out "$Scenario.json"
java -cp $build SimMain $Scenario | Set-Content -Encoding UTF8 $json
if ($LASTEXITCODE -ne 0) { throw 'SimMain failed' }

python (Join-Path $here 'render.py') $json
if ($LASTEXITCODE -ne 0) { throw 'render failed' }
