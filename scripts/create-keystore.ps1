# Creates upload-keystore.jks + keystore.properties at the repo root (gitignored).
# Run once. Back up the JKS and passwords offline.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\create-keystore.ps1
#
# The DN fields below are certificate metadata only (not a Google account).
# Edit them if you want, or leave the defaults.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jks = Join-Path $root "upload-keystore.jks"
$props = Join-Path $root "keystore.properties"

# --- Certificate identity (Play upload key only; not shown on the store page) ---
# Keep this generic so nothing personal lands in the git-tracked script.
$cn = "SonyScreen"
$ou = "Mobile"
$o  = "SonyScreen"
$l  = "Private"
$st = "NA"
$c  = "FR"
$dname = "CN=$cn, OU=$ou, O=$o, L=$l, ST=$st, C=$c"

if (Test-Path $jks) {
    Write-Error "upload-keystore.jks exists already. Aborting to avoid overwrite."
}
if (Test-Path $props) {
    Write-Error "keystore.properties exists already. Aborting to avoid overwrite."
}

$keytool = $null
$candidates = @(
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
    "$env:JAVA_HOME\bin\keytool.exe",
    "C:\Program Files\Java\*\bin\keytool.exe"
)
foreach ($cPath in $candidates) {
    $resolved = Get-Item $cPath -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($resolved) { $keytool = $resolved.FullName; break }
}
if (-not $keytool) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) {
    Write-Error "keytool introuvable. Installez un JDK ou Android Studio (JBR)."
}

Write-Host "Keytool : $keytool"
Write-Host "Certificat : $dname"
Write-Host "Alias     : upload"
Write-Host ""
Write-Host "Choisis un mot de passe fort (a retenir / sauvegarder hors ligne)."
Write-Host "Il servira a la fois pour le keystore et pour la cle."
Write-Host ""

$secure = Read-Host "Mot de passe keystore" -AsSecureString
$secure2 = Read-Host "Confirmer le mot de passe" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$bstr2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure2)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $password2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr2)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr2)
}

if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 6) {
    Write-Error "Mot de passe trop court (min. 6 caracteres)."
}
if ($password -ne $password2) {
    Write-Error "Les mots de passe ne correspondent pas."
}

& $keytool -genkeypair -v `
    -keystore $jks `
    -storetype PKCS12 `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -alias upload `
    -dname $dname `
    -storepass $password `
    -keypass $password

if ($LASTEXITCODE -ne 0) {
    if (Test-Path $jks) { Remove-Item $jks -Force }
    exit $LASTEXITCODE
}

$propsText = @"
storeFile=upload-keystore.jks
storePassword=$password
keyAlias=upload
keyPassword=$password
"@
# UTF-8 without BOM — a BOM breaks Properties.load ("storeFile" becomes null).
[IO.File]::WriteAllText($props, $propsText.Trim() + "`n", [Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "OK: $jks"
Write-Host "OK: $props"
Write-Host "Ces fichiers sont gitignores. Sauvegarde-les hors ligne."
