# Format d'import cible pour l'app

## Etat actuel

Le dataset complet a ete genere dans:
- `offline-data/full/optimized`
- `offline-data/full/room-import`

Le scrape complet contient:
- `3791` canyons
- `11021` geo-points
- `1922` ressources bibliographiques uniques
- `528` textes de reglementation uniques

## Deux formats produits

### 1) `optimized/`

Format conseille pour le transport et l'embarque dans les assets.

Points forts:
- index leger pour recherche/carte
- shards pour les details
- dedup des biblios et reglementations

### 2) `room-import/`

Format conseille pour l'import en base locale.

Fichiers:
- `canyons.json`
- `geo_points.json`
- `bibliography_entries.json`
- `canyon_bibliography.json`
- `regulation_texts.json`
- `canyon_regulations.json`

## Mapping avec la base Room actuelle

### Tables deja compatibles

- `canyons`
  - reprend toutes les colonnes existantes de `CanyonEntity`
  - ajoute deja dans le JSON des champs futurs: `communes`, `bassin`, `coursEau`, `geologie`, `historique`, `remarques`, `hasSpecificRegulation`, `isForbidden`

- `geo_points`
  - compatible avec `GeoPointEntity`
  - ne fournit pas `id` pour laisser Room auto-generer

## Tables/fichiers pour la vNext

Le schema actuel Room ne sait pas encore stocker:
- `communes[]`
- `bassin`
- `coursEau`
- `geologie`
- `historique`
- `remarques`
- `bibliography`
- `reglementation`

Je recommande une migration Room avec:

- extension de `canyons`
  - `communesJson TEXT`
  - `bassin TEXT`
  - `coursEau TEXT`
  - `geologie TEXT`
  - `historique TEXT`
  - `remarques TEXT`
  - `hasSpecificRegulation INTEGER NOT NULL DEFAULT 0`
  - `isForbidden INTEGER NOT NULL DEFAULT 0`

- nouvelle table `bibliography_entries`
  - `id TEXT PRIMARY KEY`
  - `kind TEXT NOT NULL`
  - `resourceType TEXT`
  - `title TEXT NOT NULL`
  - `authorsJson TEXT`
  - `publicationYear INTEGER`
  - `reference TEXT`
  - `editor TEXT`
  - `status TEXT`
  - `scale TEXT`
  - `detailUrl TEXT`
  - `url TEXT`

- nouvelle table `canyon_bibliography`
  - `canyonId INTEGER NOT NULL`
  - `bibliographyId TEXT NOT NULL`

- nouvelle table `regulation_texts`
  - `id INTEGER PRIMARY KEY`
  - `status TEXT`
  - `action TEXT`
  - `title TEXT NOT NULL`
  - `summary TEXT`
  - `remark TEXT`
  - `details TEXT`
  - `effectiveDate TEXT`
  - `textUrl TEXT`
  - `attachmentsJson TEXT`

- nouvelle table `canyon_regulations`
  - `canyonId INTEGER NOT NULL`
  - `regulationId INTEGER NOT NULL`

## Strategie d'import recommandee

1. au premier lancement, lire `offline-data/full/optimized/manifest.json`
2. importer `search-index.json` + shards details/geo-points dans Room
3. importer ensuite `bibliography_entries`, `canyon_bibliography`, `regulation_texts`, `canyon_regulations`
4. conserver `isFavorite` et autres flags utilisateur lors des reimports

## Pourquoi ne pas importer directement les `canyons/<id>.json`

- trop de petits fichiers a ouvrir cote Android
- cout I/O plus eleve
- pas de dedup biblio/reglementation

Les fichiers individuels restent utiles pour:
- debug
- verification manuelle du scrape
- regeneration partielle

## Tailles observees sur le dataset complet

- `optimized/search-index.json`: `2.21 Mo` brut, `224 Ko` gzip
- `optimized` shards details + geo-points: `12.24 Mo` brut, `2.61 Mo` gzip
- `optimized/bibliography-entries.json`: `475 Ko` brut, `71 Ko` gzip
- `optimized/regulation-texts.json`: `701 Ko` brut, `154 Ko` gzip
- `room-import/canyons.json`: `9.16 Mo` brut, `2.26 Mo` gzip
- `room-import/geo_points.json`: `1.57 Mo` brut, `190 Ko` gzip

## Recommandation finale

- embarquer `optimized/` dans l'app
- importer dans Room au premier lancement
- utiliser `room-import/` surtout comme format technique de reference pour l'implementation de l'importeur et des migrations
