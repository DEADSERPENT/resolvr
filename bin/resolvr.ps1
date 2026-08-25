# resolvr.ps1 — cross-platform launcher for the Resolvr CLI (PowerShell — Windows/macOS/Linux).
#
# This script's ONLY job is finding a JVM and handing off to the CLI jar — every actual
# behavior (status/doctor/start/stop/restart/dev) lives in Java under cli/src. Uses only
# built-in PowerShell facilities ($PSScriptRoot, Test-Path, Join-Path) — no external tools.

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$CliJar = Join-Path $RepoRoot "cli/target/resolvr-cli.jar"

if (-not (Test-Path $CliJar)) {
    Write-Host "ERROR: Resolvr CLI is not built yet." -ForegroundColor Red
    Write-Host "  Build it with:"
    Write-Host "    cd `"$RepoRoot/cli`"; ./mvnw.cmd package -DskipTests"
    exit 1
}

$JavaBin = "java"
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin/java.exe"
    if (-not (Test-Path $candidate)) {
        $candidate = Join-Path $env:JAVA_HOME "bin/java"
    }
    if (Test-Path $candidate) {
        $JavaBin = $candidate
    }
}

& $JavaBin "-Dresolvr.repo.root=$RepoRoot" -jar $CliJar @args
exit $LASTEXITCODE
