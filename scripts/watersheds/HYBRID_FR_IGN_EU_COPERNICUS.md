# Strategie hybride France IGN / Europe Copernicus

La meilleure strategie pratique pour ce projet est :

- `France`: IGN `RGE ALTI 5m` en baseline
- `France fallback leger`: IGN `BD ALTI 25m`
- `France cas delicats`: IGN `RGE ALTI 1m` en recalcul cible
- `Europe / reste hors France`: `Copernicus DEM GLO-30`
- `Monde hors couverture Copernicus retenue`: garder temporairement `MERIT Hydro` ou completer au besoin

## Pourquoi cette strategie

- `MERIT Hydro 90m` est utile pour une passe mondiale homogène, mais trop grossier pour de petits canyons francais
- `RGE ALTI 5m` offre un bien meilleur compromis precision/poids pour la France
- `BD ALTI 25m` reste une solution plus legere si on veut aller plus vite
- `RGE ALTI 1m` est excellent mais tres lourd; il faut le reserver aux cas suspects et aux canyons critiques
- `Copernicus GLO-30` est une bonne base 30m pour l'Europe hors France

## Licences et mentions

- `IGN`: conserver les mentions `© IGN - année d'édition`
- `Copernicus GLO-30`: conserver la mention `provided under COPERNICUS by the European Union and ESA`

## Workflow recommande

### 1. France baseline avec IGN RGE ALTI 5m

- telecharger les departements francais utiles depuis `https://geoservices.ign.fr/rgealti`
- construire une mosaique/VRT `RGE ALTI 5m`
- calculer localement `flow direction`, `flow accumulation`, puis `UPA`
- relancer `scripts/compute_entry_watersheds.py` sur les canyons francais avec ces rasters derives

### 2. France fallback leger avec BD ALTI 25m

- si le `RGE ALTI 5m` est trop lourd pour un premier passage, utiliser `BD ALTI 25m`

### 3. France ciblage fin avec RGE ALTI 1m

- uniquement pour les cas suspects encore ouverts
- telecharger les departements ou zones utiles depuis `https://geoservices.ign.fr/rgealti`
- recalculer localement sur une emprise limitee autour du canyon

### 4. Europe avec Copernicus GLO-30

- telecharger les geocells 1x1 degre utiles
- construire une mosaique/VRT Copernicus
- calculer `flow direction`, `flow accumulation`, puis `UPA`
- relancer `scripts/compute_entry_watersheds.py` sur les pays hors France

### 5. Fusion des runs

Utiliser `scripts/merge_country_watershed_runs.py` pour preferer IGN en France et Copernicus ailleurs.

Exemple :

```bash
python scripts/merge_country_watershed_runs.py \
  --country-run "France=build/watersheds/ign-france-run" \
  --fallback-run "build/watersheds/copernicus-europe-run" \
  --fallback-run "build/watersheds/merit-main-run" \
  --output-dir "build/watersheds/hybrid-run"
```

## Scripts utiles

- plan des sources: `scripts/plan_hybrid_watershed_sources.py`
- catalogue IGN exact: `scripts/fetch_ign_alti_catalog.py`
- manifeste IGN priorise: `scripts/plan_ign_downloads.py`
- preparation telechargement/extraction/VRT IGN: `scripts/prepare_ign_department_dem.py`
- preparation generique pays par manifest: `scripts/prepare_national_dem.py`
- plan des geocells Copernicus: `scripts/plan_copernicus_geocells.py`
- derive IGN hydrology rasters: `scripts/derive_ign_hydrology.py`
- workflow local canyon sur DEM IGN: `scripts/run_local_ign_canyon_workflow.py`
- batch resumable pour toute la base: `scripts/run_catchment_batch.py`
- telechargement Copernicus a la volee: `scripts/prepare_copernicus_dem.py`
- telechargement MERIT a la volee: `scripts/prepare_merit_hydrology.py`
- sources nationales hors France par manifest: `scripts/prepare_national_dem.py`
- calcul des entrees: `scripts/compute_entry_watersheds.py`
- diagnostic des cas suspects: `scripts/analyze_watershed_suspicious_cases.py`
- fusion des runs: `scripts/merge_country_watershed_runs.py`

## Preparation concrete des sources

### IGN

```bash
python scripts/fetch_ign_alti_catalog.py
```

Les liens exacts seront ecrits dans:

- `build/watersheds/ign-catalog/bdalti_catalog.json`
- `build/watersheds/ign-catalog/rgealti_5m_catalog.json`
- `build/watersheds/ign-catalog/rgealti_1m_catalog.json`

Pour obtenir les URLs priorisees sur les departements utiles a la base canyon :

```bash
python scripts/plan_ign_downloads.py
```

Le manifeste sera ecrit dans :

- `build/watersheds/ign-plan/ign_download_manifest.json`

Pour deriver les rasters hydrologiques a partir d'un DEM IGN deja mosaque :

```bash
python scripts/derive_ign_hydrology.py \
  --dem D:/gis/ign/vrt/ign_rgealti_5m.vrt \
  --output-dir build/watersheds/ign-france-hydrology
```

Puis lancer le calcul des bassins versants avec les rasters derives :

```bash
python scripts/compute_entry_watersheds.py \
  --upa-raster build/watersheds/ign-france-hydrology/ign_upstream_area_km2.tif \
  --flowdir-raster build/watersheds/ign-france-hydrology/ign_d8_pointer_esri.tif \
  --elevation-raster build/watersheds/ign-france-hydrology/ign_breached_dem.tif \
  --output-dir build/watersheds/ign-france-run
```

Exemple de preparation generalisee par departement :

```bash
python scripts/prepare_ign_department_dem.py \
  --dataset rgealti5m \
  --department Ain \
  --output-dir build/watersheds/ign-data
```

Batch local resumable sur toute la base :

```bash
python scripts/run_catchment_batch.py \
  --source-config scripts/watersheds/source_config.example.json \
  --output-dir build/watersheds/batch-run
```

Pour ne traiter que la France :

```bash
python scripts/run_catchment_batch.py \
  --source-config scripts/watersheds/source_config.hybrid.json \
  --output-dir build/watersheds/batch-run-france \
  --france-only
```

Pour retester une liste cible de canyons et continuer meme en cas d'erreur locale :

```bash
python scripts/run_catchment_batch.py \
  --source-config scripts/watersheds/source_config.hybrid.json \
  --output-dir build/watersheds/batch-run-france-retry \
  --france-only \
  --only-canyon-id-file scripts/watersheds/france_missing_watersheds_20260325.txt
```

Les erreurs sont journalisees dans `build/watersheds/.../errors.log`, et chaque canyon en erreur ecrit quand meme un JSON individuel avec `status = error` et la stack trace.

Pour un serveur avec beaucoup de disque/RAM/CPU, option recommandee : precharger tous les departements IGN utiles puis traiter plusieurs canyons en parallele :

```bash
python scripts/run_catchment_batch.py \
  --source-config scripts/watersheds/source_config.hybrid.json \
  --output-dir build/watersheds/batch-run-france \
  --france-only \
  --prepare-france-ign-first \
  --jobs 4
```

Avec 64 Go de RAM et 8 coeurs, `--jobs 4` est un bon point de depart. Tu peux tester `--jobs 6` si le serveur reste stable.

Le script traite un canyon a la fois, peut etre interrompu puis relance avec la meme commande, et reconstruit apres chaque canyon :

- `build/watersheds/batch-run/all_canyon_point_catchments.json`
- `build/watersheds/batch-run/import_ready_catchments.json`
- `build/watersheds/batch-run/import_ready_watersheds.json`
- `build/watersheds/batch-run/watershed_polygons.geojson`
- `build/watersheds/batch-run/summary.json`

Le pipeline stocke maintenant aussi, quand possible, un polygone de bassin versant simplifie par canyon dans les sorties batch. Cette geometrie est destinee au stockage offline puis a l'affichage dans l'application plus tard.

Le batch peut maintenant tenter de preparer automatiquement la meilleure source disponible dans cet ordre :

1. `IGN` pour la France
2. `Copernicus` pour l'Europe
3. `MERIT` en fallback

Pour `Copernicus` et `MERIT`, il faut renseigner les manifests d'URL si tu veux un telechargement completement automatique a la volee :

- `scripts/watersheds/copernicus_url_manifest.example.json`
- `scripts/watersheds/merit_url_manifest.example.json`

Pour les sources nationales hors France, le principe recommande est le meme : telecharger seulement les unites utiles et reconstruire un VRT global `_all_downloaded.vrt`, ce qui permet de traverser proprement les limites entre fichiers ou subdivisions administratives.

Manifests exemples integres pour les modeles nationaux les plus prometteurs hors France :

- `scripts/watersheds/switzerland_national_dem_manifest.example.json`
- `scripts/watersheds/spain_national_dem_manifest.example.json`
- `scripts/watersheds/austria_national_dem_manifest.example.json`
- `scripts/watersheds/slovenia_national_dem_manifest.example.json`

Ces pays sont maintenant integres dans la logique de selection du batch avant `Copernicus`.

Remarque validation : les grandes stations hydrometriques (grandes rivieres) ne donnent pas directement des verites terrain pour les petits canyons. Elles peuvent seulement servir de verification tres grossiere a l'echelle du bassin aval, pas de validation fine canyon par canyon.

Sur Debian, les scripts utilisent par defaut les executables du `PATH` (`gdalbuildvrt`, `gdal_translate`, `7z`).

### Copernicus

```bash
python scripts/plan_copernicus_geocells.py
```

La liste des geocells 1x1 a telecharger sera ecrite dans:

- `build/watersheds/copernicus-plan/copernicus_geocells.json`

## Remarque importante

Avec IGN ou Copernicus, il faut recalculer les couches hydrologiques sur le nouveau MNT. On ne peut pas reutiliser directement l'UPA de MERIT avec un DEM plus fin.
