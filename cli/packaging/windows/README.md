# Windows packaging resources

Currently empty — the MSI build (`../jpackage-windows.ps1`) uses jpackage's defaults for
everything (icon, WiX template) and passes no `--resource-dir`.

Where things would go here later, if added:

- A custom `.ico` — pass `--icon cli/packaging/windows/resolvr.ico` to jpackage-windows.ps1
  and it flows through as `--icon`.
- A hand-authored WiX fragment to register the install directory on `PATH` — jpackage has no
  built-in flag for this (JDK-8231869); it requires a `--resource-dir` containing a
  replacement/extension of jpackage's generated `main.wxs`. Not implemented in this phase —
  see `docs/INSTALLATION.md`'s PATH section for the current manual workaround.
- Code-signing material once a Windows code-signing certificate exists — see
  `docs/INSTALLATION.md`'s Signing section and the commented block in
  `.github/workflows/release-cli.yml`.
