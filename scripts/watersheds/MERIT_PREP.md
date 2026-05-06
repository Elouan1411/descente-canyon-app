# Preparation MERIT Hydro

Ce document decrit la preparation minimale des rasters `MERIT Hydro` pour lancer `scripts/watersheds/compute_entry_watersheds.py`.

## Donnees a preparer

Pour la phase 1, le script a besoin de trois couches:

- `upa`: surface amont en `km2`
- `dir`: direction d'ecoulement D8
- `elv`: altitude hydrologiquement corrigee

Selon le mode de diffusion que tu telecharges, tu peux avoir:

- soit un fichier raster multi-bandes par tuile
- soit un fichier par bande

Le script accepte les deux cas:

- si tu as un seul fichier multi-bandes, passe le meme chemin a `--upa-raster`, `--flowdir-raster` et `--elevation-raster` en ajustant les bandes
- si tu as des fichiers separes ou plusieurs tuiles, cree un `VRT` par couche

## Organisation conseillee

Exemple local:

```text
D:/gis/merit/
  raw/
    upa/
    dir/
    elv/
  vrt/
    merit_upa.vrt
    merit_dir.vrt
    merit_elv.vrt
```

## Construction des VRT

Si `gdalbuildvrt` est disponible:

```bash
gdalbuildvrt D:/gis/merit/vrt/merit_upa.vrt D:/gis/merit/raw/upa/*.tif
gdalbuildvrt D:/gis/merit/vrt/merit_dir.vrt D:/gis/merit/raw/dir/*.tif
gdalbuildvrt D:/gis/merit/vrt/merit_elv.vrt D:/gis/merit/raw/elv/*.tif
```

Sous QGIS, tu peux faire la meme chose via `Raster > Divers > Construire un raster virtuel`.

## Premier run conseille

Commence par quelques canyons multi-entrees pour verifier le comportement:

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/vrt/merit_upa.vrt \
  --flowdir-raster D:/gis/merit/vrt/merit_dir.vrt \
  --elevation-raster D:/gis/merit/vrt/merit_elv.vrt \
  --only-canyon-id 241 \
  --only-canyon-id 2136 \
  --only-canyon-id 2157 \
  --output-dir build/watersheds/merit-smoke-test
```

Ensuite, ouvre dans QGIS:

- `build/watersheds/merit-smoke-test/all_entry_points.geojson`
- `build/watersheds/merit-smoke-test/all_snapped_points.geojson`
- `build/watersheds/merit-smoke-test/entry_snap_lines.geojson`
- `build/watersheds/merit-smoke-test/suspicious_cases.json`

## Controle visuel a faire

- le snap reste proche du trace canyon
- une entree `amont` tombe en amont d'une entree `aval`
- les canyons a parties distinctes montrent des lignes de snap plausibles
- les cas `MULTI_ENTRY_SELECTION_FALLBACK` sont bien identifies

## Passage au batch complet

Quand le smoke test est bon:

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/vrt/merit_upa.vrt \
  --flowdir-raster D:/gis/merit/vrt/merit_dir.vrt \
  --elevation-raster D:/gis/merit/vrt/merit_elv.vrt \
  --output-dir build/watersheds/merit-phase-1
```

## Note licence

Le projet n'ayant pas d'objectif commercial, `MERIT Hydro` reste un bon candidat pour cette premiere passe. Il faudra simplement conserver les mentions d'attribution et reverifier le cadre si l'usage change.
