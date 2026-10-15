# Checklist Play Console — SonyScreen

Coche au fur et à mesure. Textes prêts à coller : `listing-fr.txt` / `listing-en.txt`.

## 1. Compte & appli

- [ ] Compte développeur Google Play (frais unique)
- [ ] Créer l’application → nom **SonyScreen**
- [ ] Langue par défaut : **français**
- [ ] Type : Application / Gratuit
- [ ] Déclaration : pas de publicité dans l’app

## 2. Fiche Play Store

- [ ] Coller titre + descriptions depuis `listing-fr.txt`
- [ ] (Optionnel) ajouter locale **en-US** depuis `listing-en.txt`
- [ ] Icône 512×512 : `docs/store/assets/icon-512.png`
- [ ] Feature graphic 1024×500 : `docs/store/assets/feature-graphic.png`
- [ ] Au moins **2 captures** téléphone paysage (prendre sur appareil réel avec live view)
- [ ] Catégorie : **Photographie**
- [ ] Coordonnées contact développeur (e-mail)
- [ ] URL politique de confidentialité (voir §5)

## 3. Contenu de l’application

- [ ] Classification du contenu → questionnaire → **Everyone / 3+**
- [ ] Public cible : 18+ recommandé (utilitaire photo pro) ou Tout public
- [ ] News app : Non
- [ ] COVID : Non
- [ ] Store presence : pas d’app store alternatif requis

## 4. Data safety

| Question | Réponse |
| --- | --- |
| Collecte des données utilisateur | **Non** |
| Partage des données | **Non** |
| Données chiffrées en transit | N/A (pas de réseau) |
| Les utilisateurs peuvent demander la suppression | N/A |

Permissions déclarées : aucune permission runtime sensible ; USB host matériel.

## 5. Confidentialité

1. Publier le dépôt GitHub (ou GitHub Pages).
2. URL à coller dans Play Console, au choix :
   - `https://sqwado.github.io/SonyScreen/` (si Pages activé sur `docs/`)
   - ou fichier : `https://github.com/Sqwado/SonyScreen/blob/main/PRIVACY.md`
3. Vérifier que l’URL s’ouvre sans login.

Fichier local prêt : `docs/index.html` (politique) + `PRIVACY.md`.

## 6. Accès à l’app (App access)

- [ ] « Toutes les fonctionnalités sont disponibles sans restriction »
- Ou instructions : *Brancher un Sony Alpha en PC Remote via USB-C OTG ; autoriser l’accès USB.*

## 7. Ads / Financial / Government

- [ ] Contient des pubs : **Non**
- [ ] App financière : Non
- [ ] App gouvernementale : Non

## 8. Cible API & bundle

- [ ] `targetSdk` = 37 (déjà configuré)
- [ ] Générer AAB signé : `.\scripts\build-release.ps1`
- [ ] Activer **Play App Signing**
- [ ] Uploader `app/build/outputs/bundle/release/app-release.aab`
- [ ] Remplir les notes de version (FR)

## 9. Tests pré-publication

- [ ] Installer l’AAB via internal testing track
- [ ] Brancher A7R III (ou compatible) : live view OK
- [ ] Déclenchement : flux reprend
- [ ] Miroirs / cadenas / rotation OK
- [ ] Appareil sans USB host : non listé (attendu)

## 10. Marques

- [ ] Disclaimer Sony présent dans la description longue
- [ ] Pas de logo Sony officiel dans les assets

## Générer keystore + AAB

```powershell
# Une seule fois
.\scripts\create-keystore.ps1

# À chaque release
.\scripts\build-release.ps1
```

Sauvegarder hors ligne : `upload-keystore.jks` + `keystore.properties`.
