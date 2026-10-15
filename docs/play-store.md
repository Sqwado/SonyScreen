# Publication Google Play — SonyScreen

Guide opérationnel. Checklist cochable : [`store/console-checklist.md`](store/console-checklist.md).

## Identité technique

| Champ | Valeur |
| --- | --- |
| Nom | SonyScreen |
| applicationId | `com.sqwado.sonyscreen` |
| versionName | `1.0.0` |
| versionCode | `1` |
| minSdk / targetSdk | 26 / 37 |
| USB host | `required=true` |

## Fichiers prêts

| Fichier | Usage |
| --- | --- |
| [`store/listing-fr.txt`](store/listing-fr.txt) | Coller dans la fiche FR |
| [`store/listing-en.txt`](store/listing-en.txt) | Coller dans la fiche EN |
| [`store/assets/icon-512.png`](store/assets/icon-512.png) | Icône store 512×512 |
| [`store/assets/feature-graphic.png`](store/assets/feature-graphic.png) | Feature graphic 1024×500 |
| [`store/assets/screenshot-live-view.jpg`](store/assets/screenshot-live-view.jpg) | Capture live view |
| [`store/assets/screenshot-waiting-controls.jpg`](store/assets/screenshot-waiting-controls.jpg) | Attente + commandes |
| [`store/assets/screenshot-waiting-locked.jpg`](store/assets/screenshot-waiting-locked.jpg) | Attente + UI verrouillée |
| [`store/assets/screenshot-playback.jpg`](store/assets/screenshot-playback.jpg) | Lecture sur le boîtier |
| [`index.html`](index.html) | Politique de confidentialité (GitHub Pages) |
| [`../PRIVACY.md`](../PRIVACY.md) | Même contenu en Markdown |
| [`../scripts/create-keystore.ps1`](../scripts/create-keystore.ps1) | Créer le keystore upload |
| [`../scripts/build-release.ps1`](../scripts/build-release.ps1) | Produire l’AAB signé |

## Captures d’écran (à faire sur appareil)

Play exige des **photos réelles** de l’app (pas générées ici) :

1. Live view branché, commandes visibles
2. Live view avec cadenas / miroirs
3. (Optionnel) Message « Lecture sur le boîtier »

Format : paysage, téléphone 16:9 minimum 2 images.

## Confidentialité (URL publique)

Après push GitHub, active **Settings → Pages → Deploy from branch → `/docs`**.

URL typique : `https://sqwado.github.io/SonyScreen/`

Colle cette URL dans Play Console → Politique de confidentialité.

## Signature & AAB

```powershell
.\scripts\create-keystore.ps1   # une fois
.\scripts\build-release.ps1     # à chaque release
```

Sortie : `app/build/outputs/bundle/release/app-release.aab`

Play App Signing : Google re-signe pour la distribution ; conserve ton upload key hors ligne.

## Data safety (résumé)

- Collecte : **Non**
- Partage : **Non**
- Pubs / analytics / Internet : **Non**

## Disclaimer marques

Toujours présent dans les descriptions longues. Pas de logo Sony dans les assets.
