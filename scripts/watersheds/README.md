# Calcul des bassins versants d'entree

Ce dossier contient le point de depart du pipeline pour calculer une surface amont au point `ENTREE` de chaque canyon.

## Ce que fait le script

- lit `offline-data/full/room-import/canyons.json`
- lit `offline-data/full/room-import/geo_points.json`
- evalue chaque point `ENTREE`
- snappe le point sur la cellule ayant la plus forte `UPA` dans un petit rayon
- choisit un seul point d'entree par canyon
- prefere le point le plus amont par connectivite d'ecoulement quand le raster `dir` est fourni
- sinon prend le point avec la plus petite `UPA`
- journalise les cas suspects pour revue manuelle

## Rasters attendus

Le script consomme des rasters deja prepares localement.

- `UPA` obligatoire: raster de surface amont en km2
- `DIR` optionnel mais recommande: raster de direction d'ecoulement D8
- `ELV` optionnel: raster d'altitude pour verifier l'ordre amont/aval

Pour une phase 1 non commerciale, l'option la plus simple est `MERIT Hydro`:

- `UPA` = bande `upa`
- `DIR` = bande `dir`
- `ELV` = bande `elv`

Le script attend un fichier unique par raster, typiquement un `GeoTIFF` ou un `VRT` mosaque.

## Installation

```bash
python -m pip install -r scripts/watersheds/requirements.txt
```

Preparation des rasters MERIT: voir `scripts/watersheds/MERIT_PREP.md`.

Strategie hybride France IGN / Europe Copernicus: voir `scripts/watersheds/HYBRID_FR_IGN_EU_COPERNICUS.md`.

## Execution type

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/upa.vrt \
  --flowdir-raster D:/gis/merit/dir.vrt \
  --elevation-raster D:/gis/merit/elv.vrt \
  --output-dir build/watersheds/merit-phase-1
```

Pour cibler quelques canyons pendant le debug:

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/upa.vrt \
  --flowdir-raster D:/gis/merit/dir.vrt \
  --elevation-raster D:/gis/merit/elv.vrt \
  --only-canyon-id 241 \
  --only-canyon-id 2136
```

Verification interne sans raster reel:

```bash
python scripts/watersheds/compute_entry_watersheds.py --self-check
```

## Sorties

Le script ecrit dans `build/watersheds` par defaut:

- `run_metadata.json`
- `selected_entries.json`
- `suspicious_cases.json`
- `summary.json`
- `all_entry_points.geojson`
- `all_snapped_points.geojson`
- `entry_snap_lines.geojson`
- `selected_entry_points.geojson`

Les sorties `GeoJSON` servent a controler visuellement les snaps dans QGIS ou tout autre SIG.

## Codes suspects actuellement logges

- `ENTRY_OUTSIDE_UPA_RASTER`
- `ENTRY_NO_VALID_UPA_CANDIDATE`
- `SNAP_DISTANCE_LARGE`
- `SNAP_UPA_JUMP_LARGE`
- `ENTRY_SNAPPED_TO_OUTLET_OR_SINK`
- `MULTI_ENTRY_SAME_SNAP_CELL`
- `MULTI_ENTRY_FLOW_DISCONNECTED`
- `MULTI_ENTRY_UPA_ORDER_CONFLICT`
- `MULTI_ENTRY_ELEVATION_ORDER_CONFLICT`
- `MULTI_ENTRY_LABEL_ORDER_CONFLICT`
- `MULTI_ENTRY_SELECTION_FALLBACK`
- `CANYON_NO_VALID_ENTRY_RESULT`

## Limites de cette phase

- on calcule une `surface amont`, pas encore le polygone du bassin versant
- le choix du snap est volontairement simple et doit etre audite sur les cas suspects
- les secteurs karstiques, captes, glaciaires ou fortement amenages resteront a verifier a part
- sans `DIR`, le choix du point amont retombe sur la plus petite `UPA`

## Suite logique

- affiner les heuristiques de snap sur les cas suspects
- exporter ensuite la valeur retenue dans le pipeline d'import offline
- ajouter une phase 2 de recalcul haute resolution sur les cas douteux
