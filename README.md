# SonyScreen

Moniteur USB-C pour appareils photo **Sony Alpha** en mode **PC Remote** (PTP / SDIO).

SonyScreen transforme un téléphone ou une tablette Android en retour vidéo plein écran. Testé sur **A7R III**.

> Application indépendante. Non affiliée à Sony Group Corporation.

## Fonctions

- Live view plein écran (paysage)
- Miroirs horizontal / vertical / 180°
- Verrouillage tactile (appui long 2 s sur le cadenas)
- Inversion paysage / paysage inverse
- Purge de l’objet photo en RAM après déclenchement pour reprendre le flux

## Prérequis

1. Android 8.0+ avec **USB host** (OTG)
2. Câble USB-C **data**
3. Sur le boîtier :
   - Mode **PC Remote**
   - **Ctrl avec smartphone** désactivé
   - Dest. enreg. image fixe → **PC + boîtier**
   - Format **RAW** seul
   - **Affichage auto** désactivé

## Build

### Débogage

```bash
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```

JDK recommandé : le JBR d’Android Studio (AGP a besoin de `jlink`).

### Release (Play Store)

```powershell
.\scripts\create-keystore.ps1   # une fois (génère JKS + keystore.properties)
.\scripts\build-release.ps1     # AAB signé
```

Fichier : `app/build/outputs/bundle/release/app-release.aab`

- Guide : [`docs/play-store.md`](docs/play-store.md)
- Checklist Console : [`docs/store/console-checklist.md`](docs/store/console-checklist.md)
- Confidentialité : [`PRIVACY.md`](PRIVACY.md) / [`docs/index.html`](docs/index.html)

## Structure

```
app/src/main/java/com/sqwado/sonyscreen/
  MainActivity.kt          UI TextureView + commandes latérales
  liveview/LiveViewSession.kt
  ptp/Ptp.kt               Handshake SDIO + live view + purge
  usb/SonyUsb.kt           Endpoints USB bulk / interrupt
```

## Licence

MIT — voir [`LICENSE`](LICENSE).
