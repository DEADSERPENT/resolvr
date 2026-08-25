# build-runtime.ps1 - Windows equivalent of build-runtime.sh. See that script's header
# comment for why the server's module set is a fixed, documented baseline rather than
# jdeps-computed (verified locally: jdeps against quarkus-run.jar can't see the real
# application dependencies, since Quarkus's fast-jar bootstrap classloader loads them
# dynamically at runtime, invisible to static analysis).
#
# Usage: build-runtime.ps1 -ServerDir <path> -CliJar <path> -OutDir <path>

param(
    [Parameter(Mandatory=$true)][string]$ServerDir,
    [Parameter(Mandatory=$true)][string]$CliJar,
    [Parameter(Mandatory=$true)][string]$OutDir
)

$ErrorActionPreference = "Stop"

$cliModules = & jdeps --print-module-deps --ignore-missing-deps $CliJar
Write-Host "CLI modules (jdeps-computed): $cliModules"

# jdk.charsets and jdk.zipfs are NOT optional, despite jlink/jpackage not erroring without
# them: confirmed locally that omitting jdk.charsets produces a runtime image where the
# packaged server exits immediately (code 1, zero stdout/stderr, no exception printed
# anywhere) on Windows -- JEP 400's stdout/stderr encoding falls back to the native console
# codepage there, which needs classes this module provides. jdk.zipfs is added defensively
# alongside it for the same class of "silent until you hit it" failure.
$serverModules = "java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,java.logging,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.security.auth,jdk.httpserver,jdk.management,jdk.jfr,jdk.charsets,jdk.zipfs"
Write-Host "Server modules (fixed baseline - see build-runtime.sh header for why): $serverModules"

$allModules = ("$cliModules,$serverModules" -split ',' | Sort-Object -Unique) -join ','
Write-Host "Combined module set: $allModules"

if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
& jlink --add-modules $allModules --output $OutDir --strip-debug --no-header-files --no-man-pages

Write-Host "Runtime image built at $OutDir"
& (Join-Path $OutDir "bin\java.exe") -version
