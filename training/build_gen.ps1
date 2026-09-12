# build_gen.ps1 - compile the standalone training-data generator/checker.
# No Gradle/Loom involved: AsciiActionCodec and EnglishActionRenderer have no
# Minecraft dependency, so this is plain javac + gson.

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..

javac -cp "training\lib\gson.jar" -d training\out `
    src\main\java\com\lilbuddybot\ir\AsciiActionCodec.java `
    src\main\java\com\lilbuddybot\ir\EnglishActionRenderer.java `
    training\javagen\com\lilbuddybot\training\GenerateDataset.java `
    training\javagen\com\lilbuddybot\training\CheckDecodable.java

Write-Host "Compiled to training\out"
