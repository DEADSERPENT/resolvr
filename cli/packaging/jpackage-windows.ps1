# jpackage-windows.ps1 - builds resolvr-cli-<version>-win-x64.msi from a staged jpackage
# input directory (see assemble-input.ps1) and a jlink runtime image (see build-runtime.ps1).
#
# The MSI's launcher (resolvr.exe) runs the CLI (com.resolvr.cli.Main) — it never launches
# the server jar directly. The server jar ships alongside inside the app image and is only
# ever started by the CLI's own `start` command via InstalledJarLaunchSpec, exactly the same
# fail-closed launch path a developer checkout uses (see that class's javadoc). This script
# does not read RESOLVR_API_KEY/GITHUB_TOKEN and does not pass any value derived from them to
# jpackage — nothing about the installed launcher's identity depends on secrets.
#
# --win-console is required, not optional: jpackage's default Windows launcher is a
# GUI-subsystem executable with no console attached, so stdout/stderr are silently discarded
# and $LASTEXITCODE isn't even set — every `resolvr status`/`doctor`/etc. would appear to
# produce no output at all. Confirmed locally by building an app-image without this flag and
# observing exactly that before adding it (see docs/INSTALLATION.md's local smoke-test notes).
#
# Requires the WiX Toolset (candle.exe/light.exe) on PATH — jpackage's --type msi shells out
# to it. GitHub's windows-latest hosted runners ship it preinstalled; a local dev machine may
# not have it (this repo's local smoke test used --type app-image instead for that reason;
# see docs/INSTALLATION.md's "Building installers locally" section).
#
# Known limitation: this MSI does not add the install directory to PATH. jpackage has no
# built-in flag for that (see JDK-8231869) — it requires hand-authored WiX fragments, which
# are out of scope for this phase. Documented in docs/INSTALLATION.md; users add the install
# directory's bin\ path manually, same as any unsigned/manually-registered CLI tool.
#
# Usage: jpackage-windows.ps1 -StageDir <path> -RuntimeDir <path> -Version <x.y.z> -DestDir <path>

param(
    [Parameter(Mandatory=$true)][string]$StageDir,
    [Parameter(Mandatory=$true)][string]$RuntimeDir,
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$DestDir
)

$ErrorActionPreference = "Stop"

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "Version must be X.Y.Z (numeric), got '$Version'"
}

$tempDest = Join-Path $env:TEMP "resolvr-jpackage-win-$([guid]::NewGuid())"
New-Item -ItemType Directory -Force -Path $tempDest | Out-Null

& jpackage `
    --type msi `
    --input $StageDir `
    --runtime-image $RuntimeDir `
    --dest $tempDest `
    --name resolvr `
    --app-version $Version `
    --vendor "Resolvr" `
    --description "Resolvr - local-first AI code-review resolution CLI" `
    --main-jar cli/resolvr-cli.jar `
    --main-class com.resolvr.cli.Main `
    --win-console `
    --win-menu `
    --win-shortcut `
    --win-dir-chooser

if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$built = Get-ChildItem -Path $tempDest -Filter "*.msi" | Select-Object -First 1
if (-not $built) {
    throw "jpackage did not produce an .msi in $tempDest"
}

New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
$final = Join-Path $DestDir "resolvr-cli-$Version-win-x64.msi"
Move-Item -Force $built.FullName $final

Write-Host "Built $final"
