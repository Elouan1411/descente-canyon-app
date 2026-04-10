# Overlay Architecture

## Goal

The app now supports two canyon sources merged into a single runtime catalog:

- `dc:*`: imported from Descente-Canyon
- `app:*`: created and maintained directly in this repository

The Android app still consumes a single Room dataset and displays all canyons the same way.

## Source model

### Descente-Canyon base

The existing `offline-data/full/room-import` dataset remains the upstream source of truth for base canyon data.

### App overlay

Repository-managed additions live under `data-overlay/`.

Current additive enrichments allowed for `dc:*` canyons:

- watershed area
- watershed polygon
- one or more GPX tracks

No overrides of Descente-Canyon base fields are allowed yet.

### App canyons

`app:*` canyons are complete canyon definitions owned by this repository.

## Runtime identifiers

- `dc:<id>` keeps the existing runtime integer id from Descente-Canyon
- `app:*` ids are allocated from `1000000`

Stable ids are stored in `data-overlay/runtime-id-map.json`.

## Repository layout

```text
data-overlay/
  app-canyons/
    sample-canyon/
      canyon.json
      tracks/
        main.gpx
  dc/
    1234/
      overlay.json
      tracks/
        main.gpx
      watershed.geojson
  runtime-id-map.json
  schemas/
    app-canyon.schema.json
    dc-overlay.schema.json
```

## Merge rules

### `dc:*`

- keep upstream base canyon fields unchanged
- append or fill additive overlay data
- allow multiple tracks
- allow one watershed payload per canyon

### `app:*`

- repository file is the full canyon source
- tracks and watershed are optional assets next to the canyon file

## Generated output

The merge tool regenerates `offline-data/full/room-import` with:

- `canyons.json`
- `geo_points.json`
- `bibliography_entries.json`
- `canyon_bibliography.json`
- `regulation_texts.json`
- `canyon_regulations.json`
- `watersheds.json`
- `tracks.json`
- `manifest.json`

The Android importer consumes that merged dataset at app startup.

## Submission flow

Public form submissions go through the Cloudflare Worker.

Target workflow:

1. receive structured form payload
2. create a branch in this repository
3. write either:
   - a new `app-canyons/<slug>/canyon.json`
   - or a `dc/<id>/overlay.json`
4. write uploaded GPX files into `tracks/`
5. open a draft PR
6. open a tracking issue linked to that draft PR

## Notes

- release generation is the only publication path for now
- no runtime sync is required yet
- the overlay format is intentionally additive-first to avoid drifting from Descente-Canyon updates
