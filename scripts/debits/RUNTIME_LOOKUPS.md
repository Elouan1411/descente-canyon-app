# Runtime Feature Lookups

`export_runtime_lookups.py` converts `training_features.jsonl` into a compact lookup bundle that can be embedded in the Android app.

## Output

The script writes:

- `runtime_feature_lookups.json`
  - `global`: final global priors and signal ratios
  - `regions`: per-region counts and smoothed priors
  - `massifs`: per-massif counts and signal ratios
  - `canyons`: runtime-ready scalar features for each canyon id
- `metadata.json`

The `canyons` map is designed to feed the model feature builder directly for these non-weather fields:

- `globalPastObsCount`
- `regionPastObsCount`
- `massifPastObsCount`
- `canyonPastObsCount`
- `globalPrior*`
- `regionPrior*`
- `massifPrior*`
- `canyonPrior*`
- `historical*Signal*`

## Example

```bash
python scripts/debits/export_runtime_lookups.py \
  --features-path build/debit-pipeline/training-features/training_features.jsonl \
  --output-dir build/debit-pipeline/runtime-lookups
```

The generated JSON is intended to be copied into the app assets later, alongside the exported model bundle.
