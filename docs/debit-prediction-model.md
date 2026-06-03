# Debit Prediction Model

This document describes the debit prediction model currently embedded in the app and summarizes the experiments that led to it.

## Current Embedded Model

Artifact directory: `modele_statistique/`

Files shipped with the app:

- `model.onnx`
- `feature_spec.json`
- `runtime_feature_lookups.json`
- `thresholds.json`
- `metrics.json`

Current model type:

- `catboost_mobile_ordinal_classifier`
- six ordinal labels: `SEC`, `FILET`, `CORRECT`, `GROS`, `TRES_GROS`, `CRUE`
- primary UI output: six-level ordinal debit prediction
- secondary risk bucket: `LOW`, `MEDIUM`, `HIGH`

The Android app includes `modele_statistique/` as assets through `app/build.gradle.kts`.

## Final Training Strategy

The current embedded model uses the best production-compatible candidate found so far:

- reviewed Descente-Canyon observations through `2026-05-28`;
- manually reviewed high-flow reintegrations from previously excluded `invalid` / `uncertain` rows;
- manually reintegrated high-flow rows weighted at `0.25`;
- causal historical priors rebuilt from past observations only;
- seasonal and recent-history lookup features available at runtime;
- CatBoost ordinal classifier trained with `--final-train-on-all`;
- three-class thresholds recalibrated after training;
- six-level ordinal cutpoints added for display.

The model does not currently use ERA5-Land grid weather in production.

## Runtime Features

The model still uses the standard dynamic weather and static canyon/watershed features, plus runtime lookup features.

Runtime lookup additions in this version:

- canyon/month priors;
- canyon/season priors;
- massif/month and massif/season priors;
- region/month and region/season priors;
- recent high-flow priors over `30`, `90`, and `365` days;
- canyon last observed rank;
- days since last canyon observation, computed app-side from target date.

Android support:

- `DebitFeatureBuilder` selects month and season lookup entries for the target date.
- `DebitFeatureBuilder` computes `canyonDaysSinceLastObs` from `canyonLastObservationEpochDay`.
- `OnnxDebitPredictor` supports calibrated ordinal cutpoints from `thresholds.json`.

## Thresholds

Current risk-bucket thresholds in `thresholds.json`:

| Policy | Low threshold | High threshold |
| --- | ---: | ---: |
| `balanced` | `1.85` | `2.35` |
| `prudent` | `1.85` | `2.60` |
| `safety_first` | `1.85` | `2.05` |

Current six-level ordinal cutpoints:

| Boundary | Cutpoint |
| --- | ---: |
| `SEC / FILET` | `0.70` |
| `FILET / CORRECT` | `1.50` |
| `CORRECT / GROS` | `2.60` |
| `GROS / TRES_GROS` | `3.25` |
| `TRES_GROS / CRUE` | `3.75` |

If cutpoints are absent or invalid, the app falls back to rounding the ordinal score.

## Current Evaluation

Recent reviewed evaluation set:

- source: Descente-Canyon only;
- date range: `2026-03-26` to `2026-05-28`;
- observations: `2014`;
- report: `build/debit-pipeline/post-cutoff-final-temporal-modele-statistique-evaluation-descente/reliability_post_cutoff_report.md`.

Final model metrics:

| Metric | Value |
| --- | ---: |
| Accuracy | `0.8148` |
| Balanced accuracy | `0.7945` |
| Macro F1 | `0.7808` |
| Ordinal MAE | `0.2876` |
| Ordinal RMSE | `0.4888` |
| Severe ordinal errors >= 2 | `0.0074` |
| HIGH precision | `0.7467` |
| HIGH recall | `0.6357` |
| HIGH F1 | `0.6867` |

Compared with the previous embedded model:

| Model | Accuracy | Balanced accuracy | Ordinal MAE | Severe errors | HIGH precision | HIGH recall | HIGH F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Previous embedded model | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` |
| Current embedded model | `0.8148` | `0.7945` | `0.2876` | `0.0074` | `0.7467` | `0.6357` | `0.6867` |

The current model predicts fewer `HIGH` buckets than the previous model, but does so with much higher precision and better overall ordinal quality.

## Experiments Tested

### Label Review

Reviewed high-flow observations that were previously excluded by the raw quality pipeline:

- reviewed high-flow excluded rows: `1030`;
- reintegrated as `valid`: `795`;
- confirmed `invalid`: `140`;
- left unchanged: `95`.

Overrides are centralized in `scripts/debits/observation_overrides.json`.

### Full Reviewed Labels At Weight 1.0

Result: rejected.

Reason:

- over-predicted `HIGH` massively;
- high recall reached `1.0`, but precision collapsed;
- ordinal metrics degraded sharply.

### Reviewed High-Flow Rows With Reduced Weight

Tested reintegrated high-flow sample weights: `0.50`, `0.25`, `0.10`.

Best production-compatible result before temporal features: `0.25`.

| Model | Accuracy | Balanced accuracy | Ordinal MAE | HIGH F1 |
| --- | ---: | ---: | ---: | ---: |
| Previous embedded model | `0.6807` | `0.7262` | `0.3765` | `0.5813` |
| Reviewed causal `weight=0.25` | `0.7507` | `0.7542` | `0.3737` | `0.5869` |

### Canyon-Day Target

Result: rejected for production.

The canyon-day consensus target reduced label noise but also lost useful observation-level signal.

Best no-weight variant was close on balanced accuracy, but worse on ordinal MAE and HIGH F1 than the production candidates.

### Text Abbreviation Signals

Detected and audited comment-level debit nuances such as:

- `DC-`, `DC`, `DC+`, `DC++`;
- `GD-`, `GD`, `GD+`;
- `TGD-`, `TGD`, `TGD+`;
- ranges such as `DC/GD` and `GD/TGD`.

Extraction script: `scripts/debits/enrich_text_target_signals.py`.

Result: useful for audit, not adopted as hard label replacement.

Reason:

- hard replacement slightly improved ordinal MAE in one configuration;
- but reduced HIGH recall and did not improve HIGH F1;
- true soft-rank modelling remains future work.

Soft-rank follow-up was tested with HGB and CatBoost regressors, using both all extracted text signals and high-confidence abbreviation-only signals.

| Candidate | Target text signals | Ordinal MAE | HIGH recall | HIGH F1 |
| --- | --- | ---: | ---: | ---: |
| Embedded temporal-history model | none | `0.3608` | `0.6371` | `0.6138` |
| HGB soft-rank | phrases + abbreviations | `0.3815` | `0.6153` | `0.5535` |
| HGB soft-rank | abbreviations only | `0.3788` | `0.6333` | `0.5562` |
| CatBoost regressor soft-rank | phrases + abbreviations | `0.3811` | `0.6106` | `0.5590` |
| CatBoost regressor soft-rank | abbreviations only | `0.3786` | `0.6217` | `0.5587` |

Conclusion: the tested soft-rank regressors do not beat the embedded temporal-history classifier. Keep text signals as audit/label-quality metadata for now.

### ERA5-Land Grid Weather

Implemented:

- watershed-intersecting ERA5-Land grid cells;
- monthly cell fetches with VPN/IP rotation;
- area-weighted canyon daily weather aggregation;
- point fallback for canyons without watershed geometry;
- spatial precipitation features.

Scripts:

- `scripts/debits/build_weather_grid_cells.py`
- `scripts/debits/plan_weather_grid_windows.py`
- `scripts/debits/aggregate_grid_weather_by_canyon.py`
- `scripts/debits/run_open_meteo_vpn_chunks.py`

Result: rejected for production for now.

ERA5-Land v2 improved raw accuracy but degraded key safety/ordinal metrics:

| Model | Accuracy | Balanced accuracy | Ordinal MAE | HIGH recall | HIGH F1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current embedded temporal-history model | `0.8148` | `0.7945` | `0.2876` | `0.6357` | `0.6867` |
| ERA5-Land grid v2 recalibrated | `0.7284` | `0.7327` | `0.4037` | `0.6171` | `0.5589` |

### Six-Level Cutpoints

Simple unconstrained cutpoints were rejected earlier because they collapsed high-level classes.

Constrained cutpoints were then tested and adopted for display because they improved recent discrete ordinal display metrics:

- six-level accuracy: `0.7944 -> 0.7999`;
- discrete MAE: `0.2314 -> 0.2234`;
- severe discrete errors: `0.0218 -> 0.0184`.

### Segment Thresholds And Risk Policies

Segment-specific `HIGH` thresholds were tested on the recent reviewed evaluation rows as a decision-layer experiment:

| Strategy | Accuracy | Balanced accuracy | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Final default thresholds | `0.8148` | `0.7945` | `0.7467` | `0.6357` | `0.6867` | `229` |
| Month-specific HIGH threshold | `0.8098` | `0.8229` | `0.6700` | `0.7546` | `0.7098` | `303` |
| Country-specific HIGH threshold | `0.8088` | `0.8213` | `0.6667` | `0.7509` | `0.7063` | `303` |
| Season-specific HIGH threshold | `0.8093` | `0.8109` | `0.6821` | `0.7100` | `0.6958` | `280` |

These segment thresholds improve `HIGH` recall and F1 on the recent slice, but they were not adopted because the thresholds were fitted directly on the same recent evaluation period. They are promising and should be recalibrated on a proper calibration split before production use.

The standard policy comparison on the final model is:

| Policy | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: |
| `balanced` | `0.7467` | `0.6357` | `0.6867` | `229` |
| `prudent` | `0.8750` | `0.4944` | `0.6318` | `152` |
| `safety_first` | `0.3964` | `0.8959` | `0.5496` | `608` |

`balanced` remains the default policy.

### Uncertainty And Probability Calibration

The final model's recent high-flow probability calibration is reasonably good:

- HIGH Brier score: `0.0584`.
- HIGH expected calibration error: `0.0237`.
- Remaining `CRUE` not predicted as `HIGH`: `5/43`.

Uncertainty/range display remains a product opportunity, but no alternative uncertainty policy was adopted in this pass.

### Ensembles And Specialist Models

The model now has strong temporal-history features. A lightweight strategy analysis was added in `scripts/debits/analyze_prediction_strategies.py` to evaluate segment thresholds and future ensembles from prediction JSONL files.

Full multi-model ensembles and mixture-of-experts were not adopted in this pass because the best available alternative candidates (`ERA5-Land`, hard text-abbreviation labels, canyon-day, soft-rank regressors) did not beat the final embedded model as standalone models. Future ensemble tests should compare against this final baseline and use a proper calibration/validation split.

## Known Limitations

- Runtime lookup artifact is larger than before: around `18.7 MB`.
- The model still does not use ERA5-Land grid weather at runtime.
- The remaining severe errors are mostly `CRUE` under-estimations.
- The app still keeps `LOW/MEDIUM/HIGH` as a secondary risk bucket for safety metrics and policy thresholds.

## Useful Commands

Compile app:

```bash
bash gradlew :app:compileDebugKotlin
```

Evaluate current embedded model on recent reviewed observations:

```bash
.venv/bin/python scripts/debits/evaluate_post_cutoff_app_model.py \
  --model-dir modele_statistique \
  --observations-path build/debit-pipeline/post-cutoff-descente-refresh-reviewed/valid_debit_observations.jsonl \
  --output-dir build/debit-pipeline/post-cutoff-final-temporal-modele-statistique-evaluation-descente \
  --weather-cache-dir build/debit-pipeline/post-cutoff-app-model-evaluation-descente/weather-cache \
  --request-delay-ms 0 \
  --timeout-s 120
```

Export excluded high-flow review list:

```bash
.venv/bin/python scripts/debits/export_excluded_high_flow_review.py
```

Apply reviewed high-flow decisions:

```bash
.venv/bin/python scripts/debits/apply_excluded_high_flow_review.py
```

## Future Work

Recommended next experiments:

- true soft-rank model using `textTargetRank` rather than hard label replacement;
- targeted treatment of remaining `CRUE` under-estimations;
- runtime lookup compression, likely gzip asset loading;
- ERA5-Land revisit only if a model can exploit it without degrading HIGH recall and ordinal MAE.
