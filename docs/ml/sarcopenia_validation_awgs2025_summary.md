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

## Performance

| variant | evaluation | AUROC | AUPRC | Brier | ECE |
| --- | --- | ---: | ---: | ---: | ---: |
| with waist | 5-fold cross-validation | 0.835 | 0.459 | 0.091 | 0.008 |
| with waist | random 80/20 holdout | 0.820 | — | 0.0952 | 0.0132 |
| minimal | evaluation summary | 0.822 | — | — | — |

The holdout calibration curve closely followed the identity line. The Brier and ECE results support preserving the
continuous probability for longitudinal use. This is internal validation; it does not turn the output into a clinical
diagnosis or replace external validation on the production population.

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
