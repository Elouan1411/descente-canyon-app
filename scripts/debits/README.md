# Debit Modelling Pipeline

This pipeline prepares a hydrology-safe training dataset from descente-canyon.com debit reports.

It assumes every observation is attached to the local day at `08:00`.

## Outputs

The pipeline is split into four steps:

1. `build_debit_observation_dataset.py`
   - scrapes canyon debit pages
   - reuses a persistent HTML cache in `build/debit-pipeline/cache/debit-html`
   - automatically reuses an older cache in `<output-dir>/html-cache` if it already contains more files
   - parses one observation event per debit row
   - applies conservative quality filtering
   - excludes known false debit posts via `scripts/debits/observation_overrides.json`

2. `plan_debit_weather_windows.py`
   - selects one weather target per canyon
   - uses watershed bbox center when available
   - falls back to entry / upstream parking / other geo points
   - plans `J-7 08:00 -> J 08:00` observation windows for feature extraction
   - builds one historical fetch window per target by default with `--fetch-strategy history_daily`

3. `fetch_open_meteo_archive.py`
   - fetches daily historical weather from Open-Meteo archive
   - batches multiple targets into one Open-Meteo request with `--max-batch-targets`
   - resumes from `weather_window_manifest.jsonl` and `raw-json/` on rerun
   - writes flattened daily rows incrementally to avoid losing progress
   - retries rate limits / temporary API failures with exponential backoff

4. `build_debit_training_features.py`
   - joins valid observations with daily weather cache
   - computes extended daily hydrology features for modelling
   - adds temporal priors by canyon / massif / region using past observations only
   - adds heuristic historical flags for regulated and snowmelt-sensitive canyons

5. `train_debit_baseline_model.py`
    - supports `random_forest` and `catboost`
    - defaults to `3` classes: `LOW`, `MEDIUM`, `HIGH`
    - uses temporal `train / calibration / test` splits for calibrated probability outputs
    - reports `HIGH` threshold policies for balanced and prudent operating modes

## Example

```bash
python scripts/build_debit_observation_dataset.py --all --workers 6 --output-dir build/debit-pipeline/observations
python scripts/plan_debit_weather_windows.py --output-dir build/debit-pipeline/weather-planning --fetch-strategy history_daily
python scripts/fetch_open_meteo_archive.py --output-dir build/debit-pipeline/weather-archive --workers 1 --max-batch-targets 25 --request-delay-ms 5000
python scripts/build_debit_training_features.py --output-dir build/debit-pipeline/training-features
python scripts/train_debit_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --model random_forest --calibration-method sigmoid
python scripts/train_debit_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-catboost-v23 --model catboost --calibration-method sigmoid
```

To force a fresh debit download, add:

```bash
python scripts/build_debit_observation_dataset.py --all --refresh-html-cache
```

If you only changed the filtering rules and want to relabel an existing dataset without re-reading all cached HTML:

```bash
python scripts/build_debit_observation_dataset.py --all \
  --reuse-observations-path build/debit-pipeline/observations/all_debit_observations.jsonl \
  --output-dir build/debit-pipeline/observations
```

If the weather archive fetch is interrupted, rerun the exact same command. Already completed target histories are skipped automatically.

For the CatBoost variant, install:

```bash
python -m pip install catboost
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
- The historical weather fetch defaults to Open-Meteo `era5` daily data for temporal consistency and fewer API calls.
- The generated JSONL files are easy to import into DuckDB or Parquet conversion jobs on the server.
