# AWGS 2025 Deployment Validation Summary

## Setup

- Source: KNHANES 2022–2024
- Population: age 65 or older with concurrent BIA and grip-strength measurements
- Labeled sample: 4,279 participants; 568 positive cases; observed prevalence 13.27%
- Target: `sarcopenia_awgs2025`
- Definition: low ASMI **or** low ASM/BMI, together with low grip strength
- Model: unweighted logistic regression using app-collectable inputs

The final deployment artifacts were fitted on all available 2022–2024 labeled rows. Validation results below come
from cross-validation or a held-out random 80/20 split, not from the final full-data fit itself.

### Why the evaluation strategy changed

The previous v1 model trained on 2022–2023 and treated 2024 as a temporal external-validation set. That result cannot
be carried forward as validation of v2 because AWGS 2025 changes the target population, and the final v2 artifacts use
all three years for training. For v2, 2022–2024 were pooled and evaluated with stratified cross-validation plus a
random 80/20 holdout before the final full-data fit. This prioritizes a stable number of positive cases: the pooled
AWGS 2025 cohort has 568 positives, which would become substantially smaller and less stable if a single year were
reserved. The reported v2 results are therefore internal validation, not a replacement claim for temporal or external
validation. A future untouched cohort remains desirable.

## Performance

| variant | evaluation | AUROC | AUPRC | Brier | ECE |
| --- | --- | ---: | ---: | ---: | ---: |
| with waist | 5-fold cross-validation | 0.835 | 0.459 | 0.091 | 0.008 |
| with waist | random 80/20 holdout | 0.820 | — | 0.0952 | 0.0132 |
| minimal | evaluation summary | 0.822 | — | — | — |

The holdout calibration curve closely followed the identity line. The Brier and ECE results support preserving the
continuous probability for longitudinal use. This is internal validation; it does not turn the output into a clinical
diagnosis or replace external validation on the production population.

## Continuous score and transitional threshold

The former runtime already persisted a continuous `risk_score`, but API responses and the client primarily consumed
three tiers through `care_stage`. The product direction now requires change-over-time visualization, so downstream
work will preserve and expose the calibrated continuous value rather than collapsing every observation to a tier.

`selected_threshold=0.20` is retained as a transitional high-tier/action boundary so the existing `risk_level` and
`care_stage` pipeline continues to operate until the continuous API and client migration are complete. The current
predictor derives its middle compatibility band at half that threshold (`0.10`). Neither boundary defines the
continuous graph scale, and the planned trend must use `risk_score` together with `model_version`.

## Variant policy

- Use `self_report_plus_waist` when `waist_cm` is present.
- Use `self_report_minimal` when waist circumference is unavailable.
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
target, threshold `0.20`, and v2 model versions.

### SHA-256

| artifact | SHA-256 |
| --- | --- |
| `sarcopenia_model_minimal.joblib` | `D4ECC8980E61403E5CEA1338A0D02F12ED361D98FDA2D78799B1B8B0B5B40D08` |
| `sarcopenia_model_with_waist.joblib` | `3271F0B0C057B1149713281BDB0DACD3A81B52BD284D99A05973E7CF79FA4E14` |

## Interpretation limits

- AWGS 2025 and the former target define different positive populations, so their discrimination metrics should not
  be interpreted as a direct model-quality contest.
- The labeled sample selection can under-represent frailer and oldest participants.
- App users may have a different input distribution from KNHANES participants.
- Model-version boundaries must be retained when continuous scores are later exposed as a trend.
- The former temporal/DXA validation record is preserved under `docs/ml/archive/` as v1 historical evidence; it must
  not be presented as validation of the AWGS 2025 v2 artifacts.
