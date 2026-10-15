# Builds a signed Play Store AAB (requires keystore.properties + upload-keystore.jks).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    Write-Error "keystore.properties manquant. Lancez d'abord .\scripts\create-keystore.ps1"
}
if (-not (Test-Path (Join-Path $root "upload-keystore.jks"))) {
    Write-Error "upload-keystore.jks manquant. Lancez d'abord .\scripts\create-keystore.ps1"
}

& (Join-Path $root "gradlew.bat") ":app:bundleRelease"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$aab = Join-Path $root "app\build\outputs\bundle\release\app-release.aab"
if (-not (Test-Path $aab)) {
    Write-Error "AAB introuvable: $aab"
}

Write-Host ""
Write-Host "AAB prêt pour Play Console:"
Write-Host $aab
Get-Item $aab | Format-List FullName, Length, LastWriteTime
