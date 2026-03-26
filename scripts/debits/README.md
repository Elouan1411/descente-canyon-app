# Debit Modelling Pipeline

This pipeline prepares a hydrology-safe training dataset from descente-canyon.com debit reports.

It assumes every observation is attached to the local day at `08:00`.

## Outputs

The pipeline is split into four steps:

1. `build_debit_observation_dataset.py`
   - scrapes canyon debit pages
   - parses one observation event per debit row
   - applies conservative quality filtering
   - excludes known false debit posts via `scripts/debits/observation_overrides.json`

2. `plan_debit_weather_windows.py`
   - selects one weather target per canyon
   - uses watershed bbox center when available
   - falls back to entry / upstream parking / other geo points
   - plans `J-7 08:00 -> J 08:00` weather windows and merges overlaps per target

3. `fetch_open_meteo_archive.py`
   - fetches merged historical windows from Open-Meteo archive
   - caches raw API payloads and writes flattened hourly rows

4. `build_debit_training_features.py`
   - joins valid observations with hourly weather cache
   - computes precipitation features for modelling

5. `train_debit_baseline_model.py`
   - optional baseline with `scikit-learn`
   - defaults to `3` classes: `LOW`, `MEDIUM`, `HIGH`

## Example

```bash
python scripts/build_debit_observation_dataset.py --all --workers 6 --output-dir build/debit-pipeline/observations
python scripts/plan_debit_weather_windows.py --output-dir build/debit-pipeline/weather-planning
python scripts/fetch_open_meteo_archive.py --output-dir build/debit-pipeline/weather-archive --workers 4
python scripts/build_debit_training_features.py --output-dir build/debit-pipeline/training-features
python scripts/train_debit_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl
```

## Quality Filter

The filter is intentionally precision-first:

- `valid`
  - descended observations
  - non-descended observations with hydrology-specific comments

- `invalid`
  - explicit info-only posts unrelated to flow
  - route / access / logistics / training announcements
  - manual overrides for known bad examples

- `uncertain`
  - non-descended observations without enough signal
  - rows with an unparsed date

Keep `invalid` and `uncertain` rows for manual review. Only `valid` rows should feed the training set.

## Notes

- The weather target logic mirrors the app: watershed center first, then canyon geo points.
- The historical weather fetch defaults to Open-Meteo `era5` for temporal consistency.
- The generated JSONL files are easy to import into DuckDB or Parquet conversion jobs on the server.
