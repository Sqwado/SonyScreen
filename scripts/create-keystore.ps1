# Creates upload-keystore.jks + keystore.properties at the repo root (gitignored).
# Run once. Back up the JKS and passwords offline.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jks = Join-Path $root "upload-keystore.jks"
$props = Join-Path $root "keystore.properties"

if (Test-Path $jks) {
    Write-Error "upload-keystore.jks exists already. Aborting to avoid overwrite."
}

$keytool = $null
$candidates = @(
    "$env:JAVA_HOME\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
    "C:\Program Files\Java\*\bin\keytool.exe"
)
foreach ($c in $candidates) {
    $resolved = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($resolved) { $keytool = $resolved.FullName; break }
}
if (-not $keytool) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) {
    Write-Error "keytool introuvable. Installez un JDK ou Android Studio (JBR)."
}

Write-Host "Keytool: $keytool"
Write-Host "Vous allez saisir mot de passe store, alias 'upload', et infos certificat."
Write-Host ""

& $keytool -genkeypair -v `
    -keystore $jks `
    -storetype JKS `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -alias upload

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$secure = Read-Host "Retapez le mot de passe du keystore (pour keystore.properties)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

@"
storeFile=upload-keystore.jks
storePassword=$password
keyAlias=upload
keyPassword=$password
"@ | Set-Content -Path $props -Encoding UTF8

Write-Host ""
Write-Host "OK: $jks"
Write-Host "OK: $props"
Write-Host "Ces fichiers sont gitignorés. Sauvegardez-les hors ligne."
