# Sarcopenia Risk Model Summary

## Purpose

The service uses a KNHANES-based model to produce a continuous sarcopenia screening probability. The value is an
internal screening signal, not a medical diagnosis. User-facing wording and longitudinal visualization are handled by
the API and client layers.

## AWGS 2025 days deployment

The deployed artifacts were trained on KNHANES 2022-2024 participants aged 65 or older. The target follows the AWGS
2025 BIA definition: low height-adjusted muscle mass or low BMI-adjusted muscle mass, together with low grip strength.
Grip strength and BIA measurements define the target only and are not model inputs.

The activity inputs use day counts instead of binary weekly-practice flags so the score can respond to walking and
strength-challenge success counts in the longitudinal trend feature.

| artifact | model version | feature set | use case |
| --- | --- | --- | --- |
| `sarcopenia_model_minimal.joblib` | `sarcopenia_lr_self_report_minimal_days_awgs2025_days_v3` | `self_report_minimal_days` | waist circumference unavailable |
| `sarcopenia_model_with_waist.joblib` | `sarcopenia_lr_self_report_plus_waist_days_awgs2025_days_v3` | `self_report_plus_waist_days` | waist circumference available |

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
- `walk_days`: 0-7 days per week with at least 30 minutes of walking
- `musc_days`: 0-5 strength-training days per week, where 5 means 5 or more days

## Integration flow

1. `HealthProfile` stores anthropometry and activity day-count fields.
2. `features_from_health_profile()` normalizes them into the deployed feature contract.
3. `RiskPredictor.predict()` selects an artifact according to waist availability.
4. The sklearn pipeline returns a continuous probability through `risk_score` plus bundle metadata.
5. The service persists the score, model version, model variant, and input snapshot.

## Longitudinal compatibility

Trend calculations should compare scores only within the same model version. When the model version changes, the first
new-version score establishes a new baseline instead of being connected to the previous version as a health change.

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
