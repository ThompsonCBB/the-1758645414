# Carcass simulator: compiles the mod's real layout code (CarcassLayout) + CarcassSim, dumps every kind x style,
# regenerates the gore textures and renders them with the vanilla models and textures.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File tools\animsim\sim_carcass.ps1 [seed]
param([long]$Seed = 1758645414)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$build = Join-Path $here 'build'
$out = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $build, $out | Out-Null

if (-not (Test-Path (Join-Path $here 'vanilla\cow_cow.png'))) { python (Join-Path $here 'extract_vanilla.py') }
python (Join-Path $root 'tools\gen_blood.py')
if ($LASTEXITCODE -ne 0) { throw 'gen_blood failed' }

javac -encoding UTF-8 -d $build (Join-Path $root 'src\main\java\com\anta\anim\CarcassLayout.java') (Join-Path $here 'CarcassSim.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$files = @()
foreach ($kind in 0..3) {
    foreach ($style in 0..3) {
        $f = Join-Path $out "carcass_${kind}_${style}.json"
        java -cp $build CarcassSim $kind $style $Seed | Set-Content -Encoding UTF8 $f
        if ($LASTEXITCODE -ne 0) { throw 'CarcassSim failed' }
        $files += $f
    }
}
python (Join-Path $here 'carcass_render.py') @files
if ($LASTEXITCODE -ne 0) { throw 'render failed' }
