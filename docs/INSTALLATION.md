# Installation

Two ways to run Resolvr:

- **Developer checkout** — clone the repo, build with Maven, run via `bin/resolvr`. Unchanged
  by this document; see `docs/DEVELOPMENT.md`. Needs Git, Maven (or the bundled `mvnw`), and a
  JDK on `PATH`/`JAVA_HOME`.
- **Installed mode** — a platform installer (MSI/pkg/deb/rpm) or a portable tarball, built by
  `.github/workflows/release-cli.yml` from a `cli-v*` tag. Needs none of the above: the
  installer bundles its own JVM (via `jlink`) and the pre-built server, so there's no Maven,
  no Git, no source checkout, and no system Java involved at all.

Both modes run the exact same server code, in the exact same fail-closed launch mode — see
[Developer checkout vs. installed mode](#developer-checkout-vs-installed-mode) below.

## Windows installation

1. Download `resolvr-cli-<version>-win-x64.msi` from the release's assets.
2. Run it. It installs under `Program Files\resolvr` by default — the directory name matches
   the packaged application name exactly (Windows paths are case-insensitive, so this doesn't
   affect anything). The installer's directory chooser lets you pick a different location, and
   it adds Start Menu / desktop shortcuts.
3. Open a terminal and run `resolvr doctor` (see [PATH](#path) below if that's not found yet).

The installer is currently **unsigned** — see [Signing](#signing). Windows SmartScreen will
show an "unrecognized publisher" warning; choose "More info" → "Run anyway" to proceed.

## macOS installation

1. Download `resolvr-cli-<version>-macos-x64.pkg` (Intel) or
   `resolvr-cli-<version>-macos-arm64.pkg` (Apple Silicon) — use `uname -m` if unsure
   (`x86_64` → x64, `arm64` → arm64).
2. Run it. It installs a jpackage-produced application bundle at `/Applications/resolvr.app`
   — macOS requires the `.app` bundle format even for a console-only tool like this one. The
   `resolvr` binary itself lives at `/Applications/resolvr.app/Contents/MacOS/resolvr`; there's
   nothing here you'd double-click from Finder.
3. Open a terminal and run `resolvr doctor` (see [PATH](#path) below).

The package is currently **unsigned and not notarized** — see [Signing](#signing). Gatekeeper
will refuse to open it with an "unidentified developer" message. Right-click (or Ctrl-click)
the installer in Finder → "Open" → "Open" to bypass this for a one-time manual install, or run
`xattr -d com.apple.quarantine <path-to-pkg>` before running it.

## Linux installation

Debian/Ubuntu:

```sh
sudo dpkg -i resolvr-cli-<version>-linux-x64.deb
```

Fedora/RHEL/openSUSE:

```sh
sudo rpm -i resolvr-cli-<version>-linux-x64.rpm
```

Both install under `/opt/resolvr` and add a menu entry. Neither package is currently signed —
see [Signing](#signing).

## Portable Linux installation

No package manager, no root required:

```sh
tar -xzf resolvr-cli-<version>-linux-x64.tar.gz    # or -linux-arm64.tar.gz
./resolvr/bin/resolvr doctor
```

This is a self-contained app-image (bundled JVM included) — extract it anywhere and run
`bin/resolvr` directly, or add `<extracted-dir>/bin` to `PATH` (see below).

**Linux ARM64 ships tar.gz only** — no `.deb`/`.rpm` for that architecture in this phase. See
[Limitations](#limitations-this-phase).

## PATH

The `.deb`/`.rpm`/`.pkg` installers do **not** currently add their install directory to
`PATH` automatically — this needs hand-authored platform installer scripting (a WiX fragment
on Windows, a postinst script on Linux) that's out of scope for this phase. Add it manually:

| Platform | Binary | Suggested PATH entry |
|---|---|---|
| Windows | `<install-dir>\resolvr.exe` | Add `<install-dir>` to your user or system `PATH` (Settings → System → About → Advanced system settings → Environment Variables) |
| macOS | `/Applications/resolvr.app/Contents/MacOS/resolvr` | `echo 'export PATH="/Applications/resolvr.app/Contents/MacOS:$PATH"' >> ~/.zshrc` |
| Linux (deb/rpm) | `/opt/resolvr/bin/resolvr` | `echo 'export PATH="/opt/resolvr/bin:$PATH"' >> ~/.bashrc` |
| Linux (portable tarball) | `<extracted-dir>/bin/resolvr` | same, pointed at wherever you extracted it |

Until then, you can always invoke the full path directly.

## start / stop / status / doctor / restart

Identical commands and output shape in both installed and developer-checkout mode:

```sh
resolvr doctor     # environment/config sanity check — run this first
resolvr start       # starts the server as a background process, waits for /q/health
resolvr status       # process, health, port, MCP endpoint, RESOLVR_API_KEY/GITHUB_TOKEN presence
resolvr stop        # stops the process Resolvr itself started
resolvr restart      # stop (tolerating "already stopped"), then start
```

`status`/`doctor` report which mode they detected — `Mode: installed (<install root>)` or
`Mode: developer checkout (<repo root>)`.

One installed-mode difference: `resolvr dev` (the live-reload dev server) is **not available**
from an installed copy — dev mode needs a source checkout to reload from. Running it from an
installed copy prints an explanatory error and exits; use `start`/`stop`/`status` instead.

Installed-mode state (PID file, log) lives in a per-user, always-writable location rather than
under the install directory (which commonly requires elevated privileges to write to, e.g.
`C:\Program Files\...`):

| Platform | State directory |
|---|---|
| Windows | `%LOCALAPPDATA%\Resolvr` |
| macOS | `~/Library/Application Support/Resolvr` |
| Linux | `$XDG_STATE_HOME/resolvr` (falls back to `~/.local/state/resolvr`) |

## RESOLVR_API_KEY

Installed mode has no separate configuration story from a developer checkout: `resolvr start`
launches the bundled server jar exactly as `-jar quarkus-run.jar` (plus `-Dquarkus.http.port`
if you've set a non-default port) and nothing else. The server itself reads
`RESOLVR_API_KEY`/`GITHUB_TOKEN` from the process environment at startup, same as always.

**The installer does not set, generate, or prompt for `RESOLVR_API_KEY`.** Set it yourself
before running `resolvr start`, the same way you would for a checkout:

```sh
export RESOLVR_API_KEY="$(openssl rand -hex 32)"   # macOS/Linux
```

```powershell
$env:RESOLVR_API_KEY = -join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
```

If `RESOLVR_API_KEY` is unset, the server refuses to start at all (`StartupSecurityCheck`) —
this is unchanged and unweakened in installed mode. See `docs/SECURITY.md`.

## GitHub authentication

Same as a checkout: set `GITHUB_TOKEN` in the environment before `resolvr start`, or (if the
`gh` CLI is installed and you've run `gh auth login`) leave it unset and the server falls back
to `gh auth token`. The installer does not touch GitHub credentials in any way.

## Uninstall

- **Windows**: Settings → Apps → "resolvr" → Uninstall (or the MSI itself, run again).
- **macOS**: no uninstaller is bundled (jpackage `.pkg` doesn't ship one) — remove
  `/Applications/resolvr.app` manually: `sudo rm -rf /Applications/resolvr.app`.
- **Linux (deb)**: `sudo dpkg -r resolvr`
- **Linux (rpm)**: `sudo rpm -e resolvr`
- **Linux (portable tarball)**: delete the extracted directory.

None of these remove your per-user state directory (PID file/log — see the table above) or
any environment variables you've set; remove those yourself if desired.

## Upgrades

There is no in-place auto-update. To upgrade: `resolvr stop` (if running), then install the
new version the same way you installed the old one — the Windows/macOS/Linux package
installers replace the previous install; for the portable tarball, extract the new one over
(or replacing) the old directory.

## Developer checkout vs. installed mode

| | Developer checkout | Installed mode |
|---|---|---|
| Launcher | `bin/resolvr` / `.cmd` / `.ps1` | native launcher (`resolvr.exe`, `resolvr`) |
| Needs Maven/Git | Yes (to build) | No |
| Needs system Java | Yes | No — bundles its own via `jlink` |
| Server launch command | `java -jar <repo>/target/quarkus-app/quarkus-run.jar` | `<bundled-java> -jar <install>/.../quarkus-run.jar` |
| `resolvr dev` | Available | Refused (needs a source checkout to reload) |
| Server security posture | Normal launch mode, fail-closed without `RESOLVR_API_KEY` | **Identical** — see below |

`RuntimeResolver` (in the CLI) decides which mode applies at runtime by checking for
`jpackage.app-path` (set automatically by an installed native launcher, absent from a
checkout) — an installed binary is never inside a git checkout, so there's no real ambiguity.
`RepoLocator`, `PackagedJarLaunchSpec`, and `QuarkusDevLaunchSpec` are unmodified by this
phase and continue to handle the checkout path exactly as before.

## Building installers locally

The Windows `.msi` build (`jpackage --type msi`) requires the WiX Toolset
(`candle.exe`/`light.exe`) on `PATH`. GitHub's `windows-latest` hosted runners have it
preinstalled; a local dev machine often doesn't. To build and smoke-test a packaged CLI locally
without WiX, build a plain jpackage **app-image** instead — the same input/runtime staging
steps `release-cli.yml` uses, just with `--type app-image` in the final jpackage call rather
than `msi`/`pkg`/`deb`/`rpm`:

```sh
./mvnw package -DskipTests                    # server (target/quarkus-app)
(cd cli && ./mvnw package -DskipTests)         # CLI jar (cli/target/resolvr-cli.jar)

bash cli/packaging/assemble-input.sh cli/target/resolvr-cli.jar target/quarkus-app build/input
bash cli/packaging/validate-input.sh build/input
bash cli/packaging/build-runtime.sh target/quarkus-app cli/target/resolvr-cli.jar build/runtime
```

Then invoke `jpackage --type app-image` directly against `build/input` and `build/runtime`,
reusing the `--name`/`--main-jar`/`--main-class` flags from `cli/packaging/jpackage-windows.ps1`
(or the macOS/Linux equivalents). On Windows use the `.ps1` scripts
(`assemble-input.ps1`/`build-runtime.ps1`) instead of the `.sh` ones.

This is how a real bug was caught during development: `InstalledJarLaunchSpec` originally
placed `-Dquarkus.http.port` *after* `-jar` on the command line, which the JVM silently treats
as a program argument instead of a system property once `-jar` is present — so the port
override was a complete no-op. Unit tests alone didn't catch it; testing against a real jlink
runtime image and a real jpackage-built launcher did. See the regression coverage in
`InstalledJarLaunchSpecTest`/`PackagedJarLaunchSpecTest`.

**Verification status of the layout `resolvr` expects from an installed copy:** `InstallationLocator`
computes installed-mode paths from `jpackage.app-path`, assuming jpackage's standard app-image
layout per OS (`<root>\resolvr.exe` / `<root>\app\` / `<root>\runtime\bin\java.exe` on Windows;
`<root>/bin/resolvr` / `lib/app/` / `lib/runtime/` on Linux; `Contents/MacOS/resolvr` /
`Contents/app/` / `Contents/runtime/Contents/Home/` on macOS). Only the **Windows** shape has
been confirmed against a real local build as described above. The **Linux and macOS** shapes
follow jpackage's documented conventions for those platforms but have not yet been verified
against a real local build in this project.

## Security

Installed mode is not a separate, weaker security posture — it reuses the same server, the
same `StartupSecurityCheck`, and the same `ApiKeyAuthFilter` a developer checkout runs.
Specifically:

- **No credentials are embedded in any installer.** The packaged input (staged by
  `cli/packaging/assemble-input.*`) is scanned by `cli/packaging/validate-input.*` before
  jpackage ever runs, checking for GitHub token shapes, PEM private keys, and resolved
  (non-placeholder) API-key/dev-profile JVM flags — the release workflow fails the build if
  any are found.
- **`resolvr start` in installed mode runs exactly** `<bundled-java> -jar <bundled
  quarkus-run.jar> [-Dquarkus.http.port=N]` — nothing else. `InstalledJarLaunchSpec` never
  adds a `-Dquarkus.profile=dev` flag, never sets/overrides `resolvr.api-key`, and never reads
  `RESOLVR_API_KEY`/`GITHUB_TOKEN` to construct the command (enforced by
  `InstalledJarLaunchSpecTest`, including an assertion that the command is a pure function of
  `(layout, port)` — proving it can't vary based on secret environment variables).
  `resolvr dev` (which does relax auth, intentionally, for live-reload development) is refused
  outright in installed mode, not just left unwired.
- **`StartupSecurityCheck` and `ApiKeyAuthFilter` are unmodified by this phase.** A packaged
  server refuses to start without `RESOLVR_API_KEY` regardless of which mode launched it, and
  every route but `/q/health` requires a valid `Authorization: Bearer <key>` regardless of
  which mode launched it.
- **The resolution/write architecture is unchanged.** `commit_and_push_resolution` is still
  the only write path, still gated on explicit human approval — see `docs/SECURITY.md`, which
  this document doesn't duplicate.

### Signing

No Windows code-signing certificate or Apple Developer ID was available when building this
phase, and none was invented. All installers are **unsigned**:

- Windows: SmartScreen shows an "unrecognized publisher" warning.
- macOS: unsigned and unnotarized — Gatekeeper blocks it by default (see
  [macOS installation](#macos-installation) for the bypass).
- Linux `.deb`/`.rpm`: unsigned packages — most package managers will warn but still allow
  installation.

The release workflow and `cli/packaging/` scripts are structured so signing can be added
later without restructuring: see the commented placeholder blocks in
`.github/workflows/release-cli.yml` and the per-platform notes in `cli/packaging/windows/`,
`cli/packaging/macos/`, and `cli/packaging/linux/`.

## Limitations (this phase)

- **No installer signing** — see [Signing](#signing) above.
- **No automatic `PATH` registration** — see [PATH](#path) above.
- **No auto-update mechanism** — see [Upgrades](#upgrades) above.
- **No macOS uninstaller** — `.pkg` doesn't ship one; remove manually.
- **Linux ARM64 ships `tar.gz` only**, not `.deb`/`.rpm` — that was the target artifact set
  requested for this phase, not a build limitation.
- **Linux ARM64 build depends on GitHub-hosted ARM64 Linux runners being available to this
  repository.** `release-cli.yml` targets them directly (`ubuntu-24.04-arm`) rather than
  cross-compiling — a real native build, not a faked one — but whether that runner label is
  actually available depends on the repository/organization's GitHub plan. If it isn't, that
  one job fails independently (`continue-on-error: true`) without blocking the Windows/macOS/
  Linux-x64 artifacts or the release itself; the release notes will simply be missing the
  arm64 tarball until it's confirmed available.
- **GraalVM native-image is out of scope for this phase** — every installer bundles a `jlink`
  JVM image plus ordinary jars, not an ahead-of-time-compiled native binary.
