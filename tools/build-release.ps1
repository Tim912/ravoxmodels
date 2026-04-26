param(
    [string]$Version = "26.1",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($JavaHome -ne "") {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}

Write-Host "[release] Java:"
java -version

Write-Host "[release] Building with Maven..."
mvn -q clean package

$releaseRoot = Join-Path $repoRoot "release"
$bundleDir = Join-Path $releaseRoot "ravoxmodels-$Version"
$zipPath = Join-Path $releaseRoot "ravoxmodels-$Version.zip"

if (Test-Path $bundleDir) {
    Remove-Item -Recurse -Force $bundleDir
}
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}

New-Item -ItemType Directory -Force -Path $bundleDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bundleDir "jars\plugins") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bundleDir "jars\sdk") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bundleDir "examples") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bundleDir "tools") | Out-Null

Copy-Item "ravoxmodels-core\target\ravoxmodels-core-$Version.jar" (Join-Path $bundleDir "jars\plugins")
Copy-Item "ravoxmodels-bridge-customore\target\ravoxmodels-bridge-customore-$Version.jar" (Join-Path $bundleDir "jars\plugins")
Copy-Item "ravoxmodels-api\target\ravoxmodels-api-$Version.jar" (Join-Path $bundleDir "jars\sdk")
Copy-Item "README.md" $bundleDir
Copy-Item "LICENSE" $bundleDir
Copy-Item "VERSION" $bundleDir
Copy-Item "ravoxmodels-core\src\main\resources\config.yml" (Join-Path $bundleDir "examples\core-config.yml")
Copy-Item "ravoxmodels-bridge-customore\src\main\resources\config.yml" (Join-Path $bundleDir "examples\bridge-config.yml")
Copy-Item "tools\converter_backend.py" (Join-Path $bundleDir "tools\converter_backend.py")
Copy-Item "tools\converter_blender_bridge.py" (Join-Path $bundleDir "tools\converter_blender_bridge.py")

Compress-Archive -Path (Join-Path $bundleDir "*") -DestinationPath $zipPath -Force

Write-Host "[release] Done:"
Write-Host "  Bundle: $bundleDir"
Write-Host "  Zip:    $zipPath"
