# Watershed Results

Tracked packaging area for watershed outputs copied from server-side `build/watersheds/...` runs.

## Structure

Each packaged run lives under:

- `watershed-results/runs/<country>/<track>/<label>/`

Suggested `track` values:

- `full`
- `retry`
- `incremental`
- `manual`
- `new-canyons`

Typical contents:

- `summary.json`
- `import_ready_catchments.json`
- `import_ready_watersheds.json`
- `watershed_polygons.geojson`
- `canyon_status_index.json`
- `failed_canyons.json`
- `errors.log`
- `source_resolution.log`
- `package_manifest.json`

## Package a run

Run this on the server or locally after copying a `build/watersheds/...` directory:

```bash
python3 scripts/package_watershed_results.py \
  --source-dir build/watersheds/batch-run-france \
  --country France \
  --track full \
  --label 2026-03-25-france-v1
```

Retry example:

```bash
python3 scripts/package_watershed_results.py \
  --source-dir build/watersheds/batch-run-france-retry \
  --country France \
  --track retry \
  --label 2026-03-25-missing-watersheds \
  --canyon-id-file scripts/watersheds/france_missing_watersheds_20260325.txt
```

## Notes

- `build/` stays ignored by Git
- `watershed-results/` is the tracked handoff area for packaging, review, and later import into the app dataset
- use one folder per run so results from different countries, retries, or future reruns do not overwrite each other
