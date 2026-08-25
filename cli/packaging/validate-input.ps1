# validate-input.ps1 - Windows equivalent of validate-input.sh. See that script's header
# for exactly what is and isn't flagged and why.
#
# Usage: validate-input.ps1 -StageDir <path>

param(
    [Parameter(Mandatory=$true)][string]$StageDir
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $StageDir -PathType Container)) {
    throw "stage dir not found: $StageDir"
}

$patterns = @(
    @{ Pattern = 'gh[ps]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'; Description = "GitHub token shape found in packaged input" },
    @{ Pattern = 'Dresolvr\.api-key=[^$][^}]'; Description = "resolved (non-placeholder) -Dresolvr.api-key= flag found in packaged input" },
    @{ Pattern = 'Dgithub\.token=[^$][^}]'; Description = "resolved (non-placeholder) -Dgithub.token= flag found in packaged input" },
    @{ Pattern = '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'; Description = "PEM private key found in packaged input" },
    @{ Pattern = 'Dquarkus\.profile=dev'; Description = "Quarkus dev-profile flag found in packaged input (installed mode must never carry one)" }
)

$files = Get-ChildItem -Recurse -File $StageDir
$fail = $false

foreach ($p in $patterns) {
    $matches = $files | Select-String -Pattern $p.Pattern -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($matches) {
        Write-Host "FAIL: $($p.Description)"
        $matches | ForEach-Object { Write-Host "  - $($_.Path)" }
        $fail = $true
    }
}

if ($fail) {
    throw "Packaging secret scan FAILED - refusing to package."
}

Write-Host "Packaging secret scan passed: no embedded credentials or dev-mode flags found."
