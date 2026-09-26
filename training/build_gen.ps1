# build_gen.ps1 - compile the standalone training-data generator/checkers.
# No Gradle/Loom involved: AsciiActionCodec and EnglishActionRenderer have no
# Minecraft dependency, so this is plain javac + gson (+ luaj for the Lua
# syntax checker).

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..

javac -cp "training\lib\gson.jar;training\lib\luaj-jse.jar" -d training\out `
    src\main\java\com\ardor\ir\AsciiActionCodec.java `
    src\main\java\com\ardor\ir\EnglishActionRenderer.java `
    training\javagen\com\ardor\training\GenerateDataset.java `
    training\javagen\com\ardor\training\CheckDecodable.java `
    training\javagen\com\ardor\training\CheckLuaScript.java

Write-Host "Compiled to training\out"
