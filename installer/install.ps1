# install.ps1 - downloads Ardor + its required mod dependencies straight into a
# Minecraft instance's mods folder. No local build/JDK needed.
#
# Usage:
#   .\install.ps1
#   .\install.ps1 -InstancePath "C:\path\to\instance"

param(
    [string]$InstancePath
)

$ErrorActionPreference = 'Stop'

$Repo = 'GabeBrPierce/Ardor'
$MinecraftVersion = '26.1.2'
$Loader = 'fabric'
$Headers = @{ 'User-Agent' = 'Ardor-Installer' }

# --- resolve the target mods folder ---

if (-not $InstancePath) {
    $cfRoot = Join-Path $env:USERPROFILE 'curseforge\minecraft\Instances'
    if (Test-Path $cfRoot) {
        $instances = Get-ChildItem $cfRoot -Directory
        if ($instances.Count -eq 1) {
            $InstancePath = $instances[0].FullName
            Write-Host "Auto-detected instance: $InstancePath"
        } elseif ($instances.Count -gt 1) {
            Write-Host "Multiple CurseForge instances found:"
            for ($i = 0; $i -lt $instances.Count; $i++) { Write-Host "  [$i] $($instances[$i].Name)" }
            $sel = Read-Host "Pick a number"
            $InstancePath = $instances[[int]$sel].FullName
        }
    }
    if (-not $InstancePath) {
        $InstancePath = Read-Host "Enter the path to your Minecraft instance folder (the one containing 'mods')"
    }
}

if ((Split-Path $InstancePath -Leaf) -eq 'mods') {
    $ModsDir = $InstancePath
} else {
    $ModsDir = Join-Path $InstancePath 'mods'
}
if (-not (Test-Path $ModsDir)) { New-Item -ItemType Directory -Path $ModsDir -Force | Out-Null }

Write-Host "Installing into: $ModsDir"
Write-Host ""

# --- Ardor + Baritone, pulled from this repo's latest release ---

$release = Invoke-RestMethod -Headers $Headers "https://api.github.com/repos/$Repo/releases/latest"
foreach ($prefix in @('ardor', 'baritone-api-fabric')) {
    $asset = $release.assets | Where-Object { $_.name -like "$prefix-*.jar" -and $_.name -notlike "*sources*" } | Select-Object -First 1
    if (-not $asset) {
        Write-Warning "Could not find a release asset matching '$prefix-*.jar' - skipping."
        continue
    }
    $dest = Join-Path $ModsDir $asset.name
    Write-Host "Downloading $($asset.name) ..."
    Invoke-WebRequest -Headers $Headers -Uri $asset.browser_download_url -OutFile $dest
}

# --- required deps, pulled from Modrinth ---

function Get-ModrinthJar {
    param([string]$Slug, [string]$DisplayName)

    $loadersParam = [uri]::EscapeDataString("[`"$Loader`"]")
    $versionsParam = [uri]::EscapeDataString("[`"$MinecraftVersion`"]")
    $uri = "https://api.modrinth.com/v2/project/$Slug/version?loaders=$loadersParam&game_versions=$versionsParam"

    $versions = $null
    try { $versions = Invoke-RestMethod -Headers $Headers $uri } catch { $versions = @() }

    if (-not $versions -or $versions.Count -eq 0) {
        Write-Warning "$DisplayName has no Modrinth build tagged for Minecraft $MinecraftVersion ($Loader). Falling back to its newest $Loader build -- verify compatibility yourself."
        $fallbackUri = "https://api.modrinth.com/v2/project/$Slug/version?loaders=$loadersParam"
        try { $versions = Invoke-RestMethod -Headers $Headers $fallbackUri } catch { $versions = @() }
    }

    if (-not $versions -or $versions.Count -eq 0) {
        Write-Warning "Could not find any $DisplayName build on Modrinth. Install it manually."
        return
    }

    $file = $versions[0].files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $versions[0].files[0] }

    $dest = Join-Path $ModsDir $file.filename
    Write-Host "Downloading $DisplayName ($($file.filename)) ..."
    Invoke-WebRequest -Headers $Headers -Uri $file.url -OutFile $dest
}

Get-ModrinthJar -Slug 'fabric-api' -DisplayName 'Fabric API'
Get-ModrinthJar -Slug 'cloth-config' -DisplayName 'Cloth Config'
Get-ModrinthJar -Slug 'modmenu' -DisplayName 'Mod Menu'

Write-Host ""
Write-Host "Done. Installed into $ModsDir" -ForegroundColor Green
Write-Host ""
Write-Host "Before you launch:"
Write-Host " - Minecraft $MinecraftVersion requires Java 25. Point your launcher's Java path at a Java 25 install."
Write-Host " - Fabric Loader 0.18.4+ must already be installed for this instance (run Fabric's own installer against Minecraft $MinecraftVersion first if you haven't)."
Write-Host " - Optional: Xaero's World Map ($MinecraftVersion) adds a highlighter overlay Ardor hooks into if present. Get it yourself from https://chocolateminecraft.com/xaerominimap.php if you want it -- Ardor works fine without it."
