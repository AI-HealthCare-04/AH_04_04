# AWGS 2025 Days Deployment Validation Summary

## Setup

- Source: KNHANES 2022-2024
- Population: age 65 or older with concurrent BIA and grip-strength measurements
- Labeled sample: 4,279 participants; 568 positive cases; observed prevalence 13.27%
- Target: `sarcopenia_awgs2025`
- Definition: low ASMI or low ASM/BMI, together with low grip strength
- Model: unweighted logistic regression using app-collectable inputs
- Activity features:
  - `walk_days`: 0-7 days per week with at least 30 minutes of walking
  - `musc_days`: 0-5 strength-training days per week, where 5 means 5 or more days

The final deployment artifacts were fitted on all available 2022-2024 labeled rows. Validation results below come
from cross-validation or a held-out random 80/20 split, not from the final full-data fit itself. The reported results
are internal validation only; this AWGS 2025 days deployment has no temporal or external validation result yet.

## Why activity inputs changed

Earlier internal AWGS 2025 model candidates used binary activity-practice inputs. The days v3 deployment uses
KNHANES-compatible day counts so the service can show a more meaningful predicted-score trend as users complete
walking and strength challenges.

KNHANES stores walking days with 0-7 day resolution, while the strength-training item is top-coded at 5 or more days.
The service therefore keeps `walk_days` on a 0-7 scale and caps only `musc_days` at 5.

## Performance

| variant | evaluation | AUROC | AUPRC | Brier | ECE |
| --- | --- | ---: | ---: | ---: | ---: |
| with waist | 5-fold cross-validation | 0.836 | - | - | - |
| minimal | 5-fold cross-validation | 0.822 | - | - | - |

The days-based activity variables are intended mainly to support longitudinal sensitivity to challenge success counts.
Overall discrimination is materially similar to the earlier binary-input AWGS 2025 candidate.

## Continuous score and transitional threshold

`selected_threshold=0.20` is retained as a transitional high-tier/action boundary so the existing `risk_level` and
`care_stage` pipeline continues to operate until the continuous API and client migration are complete. The current
predictor derives its middle compatibility band at half that threshold (`0.10`). Neither boundary defines the
continuous graph scale, and the planned trend must use `risk_score` together with `model_version`.

## Variant policy

- Use `self_report_plus_waist_days` when `waist_cm` is present.
- Use `self_report_minimal_days` when waist circumference is unavailable.
- Do not impute waist circumference merely to select the waist-aware model; the analysis did not show a benefit over
  the minimal fallback for imputed waist values.

## Artifact contract

Both joblib bundles contain:

- `model`
- `feature_columns`
- `model_name`
- `feature_set`
- `target_label`
- `selected_threshold`
- `probability_type`
- `model_version`

The repository contract tests verify these fields, the exact feature order, binary model classes, the AWGS 2025
target, threshold `0.20`, and days v3 model versions.

### SHA-256

| artifact | SHA-256 |
| --- | --- |
| `sarcopenia_model_minimal.joblib` | `987287E8BE9DAA87487595865D2113B7F298CDD3248ED107ADCD7B580AC4FFE5` |
| `sarcopenia_model_with_waist.joblib` | `EC8379081480587EC2AE757B63242D79B95FC071FCE74CAC00DA9EBE1D9B47F7` |

## Interpretation limits

- The reported v3 results are internal validation, not temporal or external validation.
- The labeled sample selection can under-represent frailer and oldest participants.
- App users may have a different input distribution from KNHANES participants.
- Model-version boundaries must be retained when continuous scores are exposed as a trend.
