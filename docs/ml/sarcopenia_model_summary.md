# Sarcopenia Risk Model Summary

## Purpose

MVP risk prediction uses a KNHANES-based sarcopenia risk model artifact for internal risk scoring.
The result is not a medical diagnosis. It is used to personalize caution messages and lifestyle missions.

## Artifacts

- `app/ml/artifacts/sarcopenia_model_minimal.joblib`
  - Version label: `sarcopenia_lr_self_report_minimal_v1`
  - Feature set: `self_report_minimal`
  - Used when waist circumference is unknown
- `app/ml/artifacts/sarcopenia_model_with_waist.joblib`
  - Version label: `sarcopenia_lr_self_report_plus_waist_v1`
  - Feature set: `self_report_plus_waist`
  - Used when waist circumference is provided
- Model family: logistic regression pipeline with sigmoid calibration
- Target: `sarcopenia_bia_label`
- Selected threshold: stored in each artifact as `selected_threshold`

## Service Runtime Inputs

The MVP service uses these app-collectable inputs:

- `age`
- `sex`: KNHANES-style numeric code, `1` for male and `2` for female
- `height_cm`
- `weight_kg`
- `bmi`
- `waist_cm`: optional
- `pa_walk_30min_5days`
- `pa_muscle_2days`

If the user selects "unknown" for waist circumference, the service uses the minimal model. If the user enters a waist
value, the service uses the waist-aware model.

## Integration Flow

1. `HealthProfile` stores birth date, sex, height, weight, BMI, waist, and activity practice flags.
2. `features_from_health_profile()` converts that ORM object to service input features.
3. `RiskPredictor.predict()` selects the artifact based on whether `waist_cm` is present.
4. The selected sklearn pipeline runs in an executor and returns score, level, message, model metadata, and input snapshot.
5. The risk prediction API should persist the result to `risk_predictions`.

The first ML PR intentionally stops at the predictor boundary. API persistence should be added in the next PR with
`physical_assessment` and `risk_prediction` services.

## Validation Reference

The source modeling package reported 2024 external validation against BIA labels:

- `self_report_full`: AUROC 0.9004, AUPRC 0.5223, Brier 0.0580
- `self_report_minimal`: AUROC 0.8841, AUPRC 0.4817, Brier 0.0609

The deployed artifacts use self-report features so the MVP can run without health-checkup upload requirements.

## Files Intentionally Excluded

The original modeling archive also contained raw KNHANES files, processed datasets, virtual environments, notebooks, and large prediction CSVs. Those are intentionally not copied into this repository.
