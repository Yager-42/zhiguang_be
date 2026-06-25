param(
    [switch]$Verify,
    [switch]$SkipDiscoveryLinks
)

$ErrorActionPreference = "Stop"

function Write-Info {
    param([string]$Message)
    Write-Host "[aegis-setup] $Message"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$methodPackRoot = Join-Path $repoRoot ".codex\\aegis"
$projectSkillRoot = Join-Path $repoRoot ".codex\\skills"
$projectConfigPath = Join-Path $repoRoot ".codex\\aegis-config.toml"
$workspaceHelper = Join-Path $methodPackRoot "scripts\\aegis-workspace.py"
$doctorScript = Join-Path $methodPackRoot "scripts\\aegis-doctor.py"

if (-not (Test-Path $methodPackRoot)) {
    throw "Missing vendored Aegis method-pack root: $methodPackRoot"
}

if (-not (Test-Path $workspaceHelper)) {
    throw "Missing Aegis workspace helper: $workspaceHelper"
}

if (-not (Test-Path $doctorScript)) {
    throw "Missing Aegis doctor script: $doctorScript"
}

if (-not $SkipDiscoveryLinks) {
    New-Item -ItemType Directory -Force -Path $projectSkillRoot | Out-Null

    Get-ChildItem -Path (Join-Path $methodPackRoot "skills") -Directory | ForEach-Object {
        $name = $_.Name
        $target = $_.FullName
        $link = Join-Path $projectSkillRoot $name

        if (Test-Path $link) {
            try {
                $existing = Get-Item $link -Force
                if ($existing.LinkType -eq "Junction" -or $existing.LinkType -eq "SymbolicLink") {
                    $resolved = [System.IO.Path]::GetFullPath($existing.Target)
                    $expected = [System.IO.Path]::GetFullPath($target)
                    if ($resolved -eq $expected) {
                        return
                    }
                    Remove-Item -LiteralPath $link -Force
                } else {
                    return
                }
            } catch {
                return
            }
        }

        cmd /c mklink /J "$link" "$target" | Out-Null
    }
}

$projectConfig = @(
    "# Project-local Aegis configuration"
    "# Regenerate with .codex/scripts/setup-aegis.ps1 after moving this repo"
    "activation_mode = `"auto`""
    "tdd_mode = `"auto`""
    "method_pack_root = `"$($methodPackRoot -replace '\\','/')`""
    "workspace_helper = `"$($workspaceHelper -replace '\\','/')`""
) -join "`n"

Set-Content -LiteralPath $projectConfigPath -Value ($projectConfig + "`n") -Encoding UTF8
Write-Info "Wrote project-local config: $projectConfigPath"

if ($Verify) {
    Write-Info "Running optional doctor verification from vendored method-pack root"
    $doctorJson = & python $doctorScript --write-config --config $projectConfigPath --discovery-root $projectSkillRoot --json
    if ($LASTEXITCODE -ne 0) {
        throw "aegis-doctor failed"
    }
    $doctorResult = $doctorJson | ConvertFrom-Json
    if (-not $doctorResult.ok) {
        throw "aegis-doctor reported ok=false"
    }
    if ($doctorResult.workspaceSupport -ne "available") {
        throw "workspaceSupport is not available"
    }
    if ($doctorResult.configStatus -ne "configured") {
        throw "configStatus is not configured"
    }
    Write-Info "Doctor verification passed"
}

Write-Info "Configured project-local config path: $projectConfigPath"
Write-Info "Vendored method-pack root: $methodPackRoot"
Write-Info "Project skill discovery root: $projectSkillRoot"
Write-Info "No user-home Aegis config or registry was written"
Write-Info "Restart Codex after first setup or after changing vendored Aegis skills."
