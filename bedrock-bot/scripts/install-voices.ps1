# install-voices.ps1 - downloads a curated set of Piper voices for the companion's Narration
# tab (plus a GLaDOS voice for Ardor's own speech) into bedrock-bot/voices/, AND the Piper
# synthesis binary itself into bedrock-bot/piper/ -- both fully self-contained under bedrock-bot/,
# nothing installed system-wide, same "bundle the binary locally" convention training/llama_server/
# already uses for llama.cpp.
#
# Separate from installer/install.ps1 on purpose: that script downloads the mod jar itself and
# is meant to run from a GitHub release with no local repo checkout. This one downloads into a
# bedrock-bot/ checkout, which only makes sense if you're actually running the companion
# (manager.js) from source.
#
# Usage:
#   .\install-voices.ps1
#   .\install-voices.ps1 -VoicesDir "D:\some\other\path" -PiperDir "D:\some\other\piper"
#
# Windows only (piper_windows_amd64.zip) -- manager.js's DEFAULT_SETTINGS.piperBinaryPath assumes
# a "piper.exe" alongside the DLLs this unzips next to it; running the companion on another OS
# needs a different piper build placed by hand and the Settings panel pointed at it instead.

param(
    [string]$VoicesDir,
    [string]$PiperDir
)

$ErrorActionPreference = 'Stop'

if (-not $VoicesDir) { $VoicesDir = Join-Path $PSScriptRoot '..\voices' }
if (-not $PiperDir) { $PiperDir = Join-Path $PSScriptRoot '..\piper' }
$VoicesDir = [System.IO.Path]::GetFullPath($VoicesDir)
$PiperDir = [System.IO.Path]::GetFullPath($PiperDir)
$CustomDir = Join-Path $VoicesDir 'custom'
New-Item -ItemType Directory -Path $VoicesDir -Force | Out-Null
New-Item -ItemType Directory -Path $CustomDir -Force | Out-Null

$Headers = @{ 'User-Agent' = 'Ardor-VoiceInstaller' }

# --- the piper binary itself ---

$PiperRelease = 'https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip'

function Install-PiperBinary {
    param([string]$DestDir)

    $exePath = Join-Path $DestDir 'piper.exe'
    if (Test-Path $exePath) {
        Write-Host "piper.exe already present, skipping."
        return
    }

    New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
    $zipPath = Join-Path $env:TEMP 'piper_windows_amd64.zip'
    $extractDir = Join-Path $env:TEMP "piper_extract_$([guid]::NewGuid().ToString('N'))"

    Write-Host "Downloading piper.exe (Piper synthesis binary, ~22MB) ..."
    Invoke-WebRequest -Headers $Headers -Uri $PiperRelease -OutFile $zipPath

    Write-Host "Extracting piper ..."
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
    # The zip's own top-level entry is a "piper/" folder holding piper.exe plus the DLLs/data files
    # it needs alongside it at runtime (espeak-ng-data, onnxruntime.dll, etc.) -- copy the whole
    # thing flat into $DestDir rather than just the exe, or synthesis fails on a missing DLL.
    $innerDir = Join-Path $extractDir 'piper'
    Get-ChildItem -Path $innerDir | Copy-Item -Destination $DestDir -Recurse -Force

    Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
}

Install-PiperBinary -DestDir $PiperDir

# --- voices ---

# Curated player voices -- Piper's own "high" quality tier only, from the official
# rhasspy/piper-voices catalog on Hugging Face. Verified against that catalog's actual directory
# listing while building this script: en_US-lessac-high and en_US-ryan-high are the ONLY two
# English voices tagged "high" in the whole catalog (amy, danny, kristin, hfc_female/male, joe,
# kusal, john, norman, bryce, sam, mike, kathleen, libritts_r, and every en_GB voice top out at
# "medium" or "low"). That's thinner variety than we'd like for pattern-matching -- see
# TODO.md's 2026-09-25 entry rather than silently shipping a "medium" voice to pad the count.
$Voices = @(
    @{ Id = 'en_US-lessac-high'; UrlBase = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/high/en_US-lessac-high' },
    @{ Id = 'en_US-ryan-high';   UrlBase = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/en_US-ryan-high' }
)

# Ardor's own voice: a community-trained ("unofficial") Piper GLaDOS model, Piper "high" tier.
# Confirmed real (.onnx + .onnx.json both present, ~114MB onnx) via a direct HEAD request against
# Hugging Face while building this script. Fan-made from Portal voice lines -- licensing is
# informal/unclear, not a cleared redistribution; see TODO.md.
$GladosVoice = @{ Id = 'en_us-glados-high'; UrlBase = 'https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/en_US/en_us-glados-high/en_us-glados-high' }

function Get-PiperVoice {
    param([string]$Id, [string]$UrlBase, [string]$DestDir)

    $onnxDest = Join-Path $DestDir "$Id.onnx"
    $jsonDest = Join-Path $DestDir "$Id.onnx.json"

    # Each file checked and fetched independently, not both-or-neither -- a real failure mode hit
    # live while building this: a flaky connection to a large-file host can drop mid-transfer after
    # the .onnx succeeds but before the small .onnx.json does (or vice versa on a retry), and the
    # old both-or-nothing check re-downloaded the already-fine 100+MB .onnx every retry instead of
    # just fetching the missing few KB.
    if (Test-Path $onnxDest) {
        Write-Host "$Id.onnx already present, skipping."
    } else {
        Write-Host "Downloading $Id.onnx ..."
        Invoke-WebRequest -Headers $Headers -Uri "$UrlBase.onnx" -OutFile $onnxDest
    }

    if (Test-Path $jsonDest) {
        Write-Host "$Id.onnx.json already present, skipping."
    } else {
        Write-Host "Downloading $Id.onnx.json ..."
        Invoke-WebRequest -Headers $Headers -Uri "$UrlBase.onnx.json" -OutFile $jsonDest
    }
}

foreach ($v in $Voices) { Get-PiperVoice -Id $v.Id -UrlBase $v.UrlBase -DestDir $VoicesDir }
Get-PiperVoice -Id $GladosVoice.Id -UrlBase $GladosVoice.UrlBase -DestDir $VoicesDir

$readmePath = Join-Path $CustomDir 'README.txt'
if (-not (Test-Path $readmePath)) {
    @'
Drop your own Piper voices here.

Each voice needs BOTH files, with matching names:
  yourvoice.onnx
  yourvoice.onnx.json

The companion scans the whole voices/ folder (including this custom/ subfolder) recursively, so
anything dropped here shows up automatically in the Narration tab's Voice Manager -- no config
file to edit. Just refresh the companion page after adding files.
'@ | Set-Content -Path $readmePath -Encoding utf8
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Piper binary: $PiperDir"
Write-Host "  Voices:       $VoicesDir"
Write-Host ""
Write-Host "manager.js's default settings already point at these locations if you didn't override"
Write-Host "-VoicesDir/-PiperDir above -- restart the companion (or edit config/settings.json by hand"
Write-Host "if it already exists from before this ran) if it doesn't pick them up automatically."
Write-Host "In the Narration tab's Voice Manager sub-tab, assign en_us-glados-high as Ardor's own voice."
