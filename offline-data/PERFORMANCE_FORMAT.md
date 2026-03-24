# Format de sortie recommande pour l'app

## Objectif

Le meilleur format pour l'app n'est pas le plus simple a lire a la main, mais celui qui:
- limite le temps de parsing au demarrage
- evite de charger un enorme JSON monolithique
- evite aussi des milliers de petits fichiers assets
- se prete bien a une importation une fois dans Room

## Recommandation

Sortie du scraper en deux vues complementaires:

### 1) Vue de validation humaine

Utilite:
- verifier que le scrape est correct
- comparer un canyon a sa page source

Format:
- `canyons/<id>.json`
- structure riche et lisible par canyon

Exemple actuel:
- `offline-data/samples/canyons/21.json`
- `offline-data/samples/canyons/23134.json`
- `offline-data/samples/canyons/2327.json`

### 2) Vue optimisee import/app

Utilite:
- charger rapidement les donnees dans l'app
- dedupliquer les textes communs
- permettre un import progressif par lots

Format recommande:
- `optimized/manifest.json`
- `optimized/search-index.json`
- `optimized/shards/canyon-details-XXXX.json`
- `optimized/shards/geo-points-XXXX.json`
- `optimized/bibliography-entries.json`
- `optimized/canyon-bibliography-links.json`
- `optimized/regulation-texts.json`
- `optimized/canyon-regulations.json`

## Pourquoi ce format est le plus adapte

### Petit index chargeable vite

`search-index.json` contient uniquement ce qui sert a:
- la recherche
- la liste
- la carte

Donc l'app peut:
- charger cet index une seule fois
- remplir Room ou une memoire cachee
- afficher la recherche sans parser tous les details topo

### Details shardes plutot qu'un seul gros fichier

Pourquoi pas un seul `all-canyons.json`:
- parsing plus lent
- pic memoire plus gros
- mise a jour plus lourde

Pourquoi pas un fichier par canyon pour la prod:
- trop de fichiers assets a ouvrir/lister
- cout I/O inutile sur Android

Compromis recommande:
- details par shards de `200` a `500` canyons
- lecture par lot pour l'import initial
- acces encore simple si on veut un import partiel

## Deduplification utile

### Reglementations

Les textes reglementaires reviennent sur plusieurs canyons.

Donc:
- stocker le texte complet une seule fois dans `regulation-texts.json`
- lier les canyons via `canyon-regulations.json`

Gain:
- moins de volume
- moins de duplication lors des updates
- meilleures perfs d'import et de stockage

### Bibliographie

Les memes topoguides reviennent aussi sur plusieurs canyons.

Donc:
- stocker les fiches biblio uniques dans `bibliography-entries.json`
- lier via `canyon-bibliography-links.json`

Le meme fichier peut contenir plusieurs types:
- `TOPOGUIDE`
- `MAP`
- `RESOURCE` pour les liens web detectes dans la section carte/biblio

## Strategie d'integration app recommandee

### Meilleure option

1. embarquer les fichiers JSON optimises dans les assets
2. au premier lancement, importer dans Room
3. ne plus lire les JSON en direct ensuite, sauf pour une mise a jour/reimport

Pourquoi c'est le mieux:
- l'app reutilise son modele actuel base sur Room
- les ecrans restent rapides
- les filtres/recherche/proximite restent SQL plutot que JSON

### Option acceptable mais moins bonne

Lire directement les JSON assets a chaque navigation.

Inconvenients:
- plus de parsing repetitif
- navigation detail moins fluide
- logique de cache a refaire dans l'app

## Taille et cout observes sur les exemples

Jeu d'exemple actuel:
- `optimized/search-index.json`: `1922` octets brut
- `optimized/shards/canyon-details-0001.json`: `8712` octets brut
- `optimized/shards/geo-points-0001.json`: `2170` octets brut
- `optimized/bibliography-entries.json`: `5141` octets brut
- `optimized/regulation-texts.json`: `2619` octets brut

Interpretation:
- l'index reste tres leger
- les details topo sont le bloc principal
- la dedup biblio/reglementation devient vite rentable a l'echelle de toute la base

## Conclusion

Le meilleur format de sortie du scraper pour les performances de l'app est:
- un index global leger pour liste/recherche/carte
- des details shardes pour l'import
- des tables dedupliquees pour `reglementation` et `bibliographie`
- un import unique dans Room au demarrage

Le dossier d'exemple genere deja cette forme cible dans `offline-data/samples/optimized`.
