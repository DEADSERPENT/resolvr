# Linux packaging resources

Currently empty — the .deb/.rpm/.tar.gz builds (`../jpackage-linux.sh`) use jpackage's
defaults for everything (icon, control/spec metadata) and pass no `--resource-dir`.

Where things would go here later, if added:

- A custom icon (`.png`) — pass `--icon cli/packaging/linux/resolvr.png` to jpackage-linux.sh
  and it flows through as `--icon`.
- Custom `postinst`/`prerm` (deb) or `%post`/`%preun` (rpm) scripts — not needed today: the
  installed layout has no install-time setup to perform (no service registration, no state
  written under the install root — see `InstalledStateDir`, which deliberately keeps runtime
  state in a per-user directory instead). Add scripts here and wire them via
  `--resource-dir cli/packaging/linux` if that ever changes (e.g. a systemd unit).
- Package signing (`.deb`/`.rpm` GPG signing) once signing infrastructure exists — see
  `docs/INSTALLATION.md`'s Signing section.
