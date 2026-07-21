# Sarcopenia Risk Model Summary

## Purpose

The service uses a KNHANES-based model to produce a continuous sarcopenia screening probability. The value is an
internal screening signal, not a medical diagnosis. User-facing wording and longitudinal visualization are handled by
the API and client layers.

## AWGS 2025 deployment

The deployed artifacts were retrained on KNHANES 2022–2024 participants aged 65 or older. The target follows the
AWGS 2025 BIA definition: low height-adjusted muscle mass **or** low BMI-adjusted muscle mass, together with low grip
strength. Grip strength and BIA measurements define the target only and are not model inputs.

### Why this replaces the previous deployment

| decision | previous deployment | AWGS 2025 v2 |
| --- | --- | --- |
| target | AWGS 2019 height-adjusted low muscle mass + low grip strength | AWGS 2025 height-adjusted **or** BMI-adjusted low muscle mass + low grip strength |
| coverage | can miss people whose absolute muscle mass is not low but is low relative to body size | includes both absolute low-muscle and sarcopenic-obesity patterns |
| data | KNHANES 2022–2023 training with 2024 temporal validation | KNHANES 2022–2024 pooled AWGS 2025 labels |
| downstream contract | continuous score existed internally, but the product consumed tiers and `care_stage` | continuous score is the primary signal for the planned longitudinal trend; tiers remain temporarily for compatibility |

This is therefore not a change from a classifier that only produced categories to a new probability-producing
algorithm. The existing logistic predictor already returned `risk_score`. The change is to deploy a newly defined,
calibrated AWGS 2025 target and make that continuous value the authoritative input for the planned change-over-time
feature instead of reducing it to three user-facing categories.

| artifact | model version | feature set | use case |
| --- | --- | --- | --- |
| `sarcopenia_model_minimal.joblib` | `sarcopenia_lr_self_report_minimal_awgs2025_v2` | `self_report_minimal` | waist circumference unavailable |
| `sarcopenia_model_with_waist.joblib` | `sarcopenia_lr_self_report_plus_waist_awgs2025_v2` | `self_report_plus_waist` | waist circumference available |

- Model family: unweighted logistic regression pipeline
- Probability type: raw `predict_proba` output
- Target label: `sarcopenia_awgs2025`
- Compatibility threshold: `0.20`
- Runtime: scikit-learn `1.6.1`

The threshold remains in the bundle for compatibility with the existing internal tier logic. Product features that
show change over time should use the continuous `risk_score` rather than treating the tier as the primary result.
With the current predictor, `0.20` is the high-tier boundary and the compatibility middle band begins at `0.10`.
Those boundaries are not the scale of the continuous trend and are not intended as the primary graph output.

## Service runtime inputs

Both variants use app-collectable inputs:

- `age`
- `sex`: KNHANES code `1` for male and `2` for female
- `height_cm`
- `weight_kg`
- `bmi`
- `waist_cm`: optional; selects the waist-aware variant when present
- `pa_walk_30min_5days`
- `pa_muscle_2days`

## Integration flow

1. `HealthProfile` stores anthropometry and activity-practice fields.
2. `features_from_health_profile()` normalizes them into the deployed feature contract.
3. `RiskPredictor.predict()` selects an artifact according to waist availability.
4. The sklearn pipeline returns a continuous probability through `risk_score` plus bundle metadata.
5. The service persists the score, model version, model variant, and input snapshot.

No predictor code change is required for this artifact update: `RiskPredictor` already reads `feature_columns`,
`selected_threshold`, `model_version`, and `feature_set` from each bundle.

## Longitudinal compatibility

AWGS 2019 v1 and AWGS 2025 v2 scores use different target definitions. A later history/visualization change must not
present a jump across those model versions as if it were a change in the user's health. Trend calculations should use
the same model version or explicitly establish a new baseline at the v2 deployment boundary.

## Validation and limitations

See [`sarcopenia_validation_awgs2025_summary.md`](sarcopenia_validation_awgs2025_summary.md) for the deployment
validation summary.

The labeled cohort requires concurrent grip-strength and BIA measurements, so the oldest participants may be
under-represented. The model is intended for users aged 65 or older; results for younger users require separate
validation. Production input drift should be monitored because app users may differ from KNHANES participants.

The modeling scripts used for this export currently remain in the model owner's separate analysis workspace
(`local_analysis/awgs2025_results`) together with non-distributable KNHANES-derived data. They are not part of this
deployment PR, so the checked-in SHA-256 values provide artifact integrity rather than full training reproducibility.
A separately shareable reproducibility package should exclude raw and processed KNHANES data.
