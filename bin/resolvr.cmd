@echo off
REM resolvr.cmd — cross-platform launcher for the Resolvr CLI (Windows CMD).
REM
REM This script's ONLY job is finding a JVM and handing off to the CLI jar — every actual
REM behavior (status/doctor/start/stop/restart/dev) lives in Java under cli/src. Uses only
REM native CMD builtins (%~dp0, if exist, setlocal) — no external tools required.

setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_ROOT=%%~fI"
set "CLI_JAR=%REPO_ROOT%\cli\target\resolvr-cli.jar"

if not exist "%CLI_JAR%" (
  echo ERROR: Resolvr CLI is not built yet.
  echo   Build it with:
  echo     cd "%REPO_ROOT%\cli" ^&^& mvnw.cmd package -DskipTests
  exit /b 1
)

set "JAVA_BIN=java"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_BIN%" -Dresolvr.repo.root="%REPO_ROOT%" -jar "%CLI_JAR%" %*
exit /b %ERRORLEVEL%
