#!/usr/bin/env bash
# validate-input.sh — packaging-time secret scan. Runs against the staged jpackage
# --input directory (see assemble-input.sh) BEFORE jpackage runs, so a build never even
# produces an installer if something got baked in that shouldn't have been.
#
# Deliberately narrow patterns, not a generic secret scanner: the config property NAME
# "resolvr.api-key" legitimately appears in the compiled server jar's bytecode (from
# @ConfigProperty(name = "resolvr.api-key")) and in application.properties as
# "resolvr.api-key=${RESOLVR_API_KEY:}" — neither is a secret, both are expected. Only
# flag shapes that would mean an actual secret VALUE got embedded:
#
#   - GitHub token shapes (ghp_/gho_/ghs_/github_pat_)
#   - a resolved (non-placeholder) -Dresolvr.api-key=... JVM flag
#   - a resolved (non-placeholder) -Dgithub.token=... JVM flag
#   - PEM private key headers
#   - a Quarkus dev-profile selector (installed mode must never carry one — belt and
#     suspenders alongside InstalledJarLaunchSpecTest's unit-level assertion of the same)
#
# Usage: validate-input.sh <stage-dir>

set -euo pipefail

STAGE_DIR="$1"
if [ -z "${STAGE_DIR:-}" ]; then
  echo "Usage: $0 <stage-dir>" >&2
  exit 1
fi
if [ ! -d "$STAGE_DIR" ]; then
  echo "ERROR: stage dir not found: $STAGE_DIR" >&2
  exit 1
fi

FAIL=0

check() {
  local pattern="$1"
  local description="$2"
  # -r recursive, -a treat binary (jars) as text, -l list matching files only,
  # -I skip binary files that grep still can't usefully scan (e.g. compressed streams
  # inside the jar it can't line-split); -a overrides that skip for the outer jar file
  # itself so a literal ASCII token embedded in it is still found.
  local matches
  matches=$(grep -rlaE "$pattern" "$STAGE_DIR" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    echo "FAIL: $description"
    echo "$matches" | sed 's/^/  - /'
    FAIL=1
  fi
}

check 'gh[ps]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}' "GitHub token shape found in packaged input"
check 'Dresolvr\.api-key=[^$][^}]' "resolved (non-placeholder) -Dresolvr.api-key= flag found in packaged input"
check 'Dgithub\.token=[^$][^}]' "resolved (non-placeholder) -Dgithub.token= flag found in packaged input"
check '\-\-\-\-\-BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY\-\-\-\-\-' "PEM private key found in packaged input"
check 'Dquarkus\.profile=dev' "Quarkus dev-profile flag found in packaged input (installed mode must never carry one)"

if [ "$FAIL" -ne 0 ]; then
  echo "Packaging secret scan FAILED — refusing to package." >&2
  exit 1
fi

echo "Packaging secret scan passed: no embedded credentials or dev-mode flags found."
