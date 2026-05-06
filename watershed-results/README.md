# Watershed Results

Tracked packaging area for watershed outputs copied from server-side `build/watersheds/...` runs.

## Structure

Each packaged run lives under:

- `watershed-results/runs/<track>/<label>/`

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

Run this on the server or locally after a world batch run is available in `build/watersheds/...`:

```bash
python3 scripts/watersheds/package_results.py \
  --source-dir build/watersheds/batch-run-world \
  --track full \
  --label 2026-03-30-world-v1
```

Retry example:

```bash
python3 scripts/watersheds/package_results.py \
  --source-dir build/watersheds/batch-run-world-retry \
  --track retry \
  --label 2026-03-30-world-retry-1
```

## Import into app dataset

Generate the app import file from the latest packaged world run:

```bash
python3 scripts/watersheds/export_room_import.py --input watershed-results
```

You can also target a specific packaged run directly:

```bash
python3 scripts/watersheds/export_room_import.py \
  --input watershed-results/runs/full/2026-03-30-world-v1
```

## Notes

- `build/` stays ignored by Git
- `watershed-results/` is the tracked handoff area for packaging, review, and later import into the app dataset
- the packaging/import flow now treats the watershed dataset as a single world batch, not as independent country runs
- use one folder per run so retries or future reruns do not overwrite each other
