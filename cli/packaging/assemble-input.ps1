# assemble-input.ps1 - Windows equivalent of assemble-input.sh. See that script's header
# for the exact shape this produces and why it matches InstallationLocator's expectations.
#
# Usage: assemble-input.ps1 -CliJar <path> -ServerQuarkusApp <path> -StageDir <path>

param(
    [Parameter(Mandatory=$true)][string]$CliJar,
    [Parameter(Mandatory=$true)][string]$ServerQuarkusApp,
    [Parameter(Mandatory=$true)][string]$StageDir
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $CliJar -PathType Leaf)) {
    throw "CLI jar not found: $CliJar"
}
if (-not (Test-Path $ServerQuarkusApp -PathType Container)) {
    throw "server quarkus-app directory not found: $ServerQuarkusApp"
}

if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir }
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "cli") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "server") | Out-Null

Copy-Item $CliJar (Join-Path $StageDir "cli\resolvr-cli.jar")
Copy-Item -Recurse $ServerQuarkusApp (Join-Path $StageDir "server\quarkus-app")

Write-Host "Staged jpackage input at $StageDir"
