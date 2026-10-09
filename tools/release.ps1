# Builds the mod and publishes the jar + changelog to the versions folder on the Desktop.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File tools\release.ps1 [-LowMemory]
# Before running: bump mod_version in gradle.properties and add a section to docs\CHANGELOG.md.
# -LowMemory builds with a 1200 MB Gradle heap (enough once the Forge cache exists; the very first build needs ~3 GB).
# Saved as UTF-8 with BOM: Windows PowerShell 5 needs it to read the Cyrillic folder name.
param([switch]$LowMemory)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Prop([string]$name) {
    (Select-String -Path gradle.properties -Pattern "^$name=(.+)$").Matches[0].Groups[1].Value.Trim()
}
$version = Prop 'mod_version'
$mc = Prop 'minecraft_version'
$jarName = "the1758645414-forge-$mc-$version.jar"

$changelog = Get-Content docs\CHANGELOG.md -Raw -Encoding UTF8
if ($changelog -notmatch "(?m)^## $([regex]::Escape($version))\b") {
    throw "docs\CHANGELOG.md has no section for version $version"
}

$desktop = [Environment]::GetFolderPath('Desktop')
$outDir = Join-Path $desktop 'Anta - версии'
$target = Join-Path $outDir $jarName
if (Test-Path $target) { throw "Version $version is already published ($target). Bump mod_version first." }

$gradleArgs = @('build', '--console=plain')
if ($LowMemory) { $gradleArgs += '-Dorg.gradle.jvmargs=-Xmx1200m' }
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item (Join-Path 'build\libs' $jarName) $target
Copy-Item docs\CHANGELOG.md (Join-Path $outDir 'ИЗМЕНЕНИЯ.md') -Force
Write-Host "Published $target"
