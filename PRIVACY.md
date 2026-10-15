# Politique de confidentialité — SonyScreen

**Dernière mise à jour :** 15 octobre 2026

SonyScreen est une application Android qui affiche le live view d’un appareil photo Sony branché en USB-C (mode PC Remote / PTP).

## Données collectées

SonyScreen **ne collecte aucune donnée personnelle**.

- Aucun compte utilisateur
- Aucune publicité
- Aucun analytics / télémétrie
- Aucun réseau Internet requis
- Aucune photo n’est enregistrée sur le téléphone

## Accès USB

L’application utilise uniquement le **hôte USB** pour ouvrir une session PTP avec le boîtier Sony. Les images de live view transitent en mémoire pour l’affichage. Lors d’une prise de vue en mode PC + boîtier, l’objet temporaire présent dans la mémoire du boîtier est lu puis **jeté** pour débloquer le live view ; le fichier RAW reste sur la carte du boîtier.

## Permissions

- **USB host** : communication avec l’appareil photo. Aucune autre permission sensible n’est demandée.

## Contact

Pour toute question relative à cette politique : ouvrez une issue sur le dépôt GitHub du projet (`Sqwado/SonyScreen`).
