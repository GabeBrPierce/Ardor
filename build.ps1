# build.ps1 - build this mod with only a JDK present.
# Uses the bundled Gradle wrapper (gradlew.bat) if a JDK is found; if no JDK is
# on PATH it tries common install locations. Output jar -> build\libs\.

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Find a JDK matching java_version in gradle.properties (fallback: any JDK on PATH)
$wantVer = (Select-String -Path 'gradle.properties' -Pattern '^java_version=(\d+)').Matches.Groups[1].Value
if (-not $env:JAVA_HOME) {
    foreach ($root in @('C:\Program Files\Eclipse Adoptium','C:\Program Files\Java','D:\Dev\Java')) {
        if (Test-Path $root) {
            $hit = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -match "jdk[-_]?$wantVer" } | Select-Object -First 1
            if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\javac.exe'))) {
                $env:JAVA_HOME = $hit.FullName; break
            }
        }
    }
}
if ($env:JAVA_HOME) {
    Write-Host "JAVA_HOME = $env:JAVA_HOME"
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
} else {
    Write-Host "No JDK $wantVer auto-detected; relying on java on PATH. Install Temurin JDK $wantVer if the build fails."
}

Write-Host "Building (first run downloads Gradle + Minecraft; be patient)..."
& .\gradlew.bat build --no-daemon --console=plain
if ($LASTEXITCODE -eq 0) {
    Get-ChildItem build\libs\*.jar -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host ("JAR: {0}" -f $_.FullName) }
}
