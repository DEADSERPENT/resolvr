# macOS packaging resources

Currently empty — the .pkg build (`../jpackage-macos.sh`) uses jpackage's defaults for
everything (icon, package layout) and passes no `--resource-dir`.

Where things would go here later, if added:

- A custom `.icns` — pass `--icon cli/packaging/macos/resolvr.icns` to jpackage-macos.sh and
  it flows through as `--icon`.
- A Developer ID Application/Installer certificate + notarization credentials, once available
  — `--mac-sign`, `--mac-signing-key-user-name`, and a post-build `xcrun notarytool submit` /
  `stapler` step would be added to `jpackage-macos.sh` and `.github/workflows/release-cli.yml`
  behind those secrets. Not implemented in this phase (no Apple Developer credentials were
  invented) — see `docs/INSTALLATION.md`'s Signing section. Until then, the .pkg is unsigned
  and triggers Gatekeeper's "unidentified developer" prompt.
