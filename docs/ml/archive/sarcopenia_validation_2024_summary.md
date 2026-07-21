# 2024 External Validation Summary (archived v1)

> This document describes the superseded AWGS 2019-era v1 model. It is retained to preserve the temporal and DXA
> validation history and must not be interpreted as validation of the AWGS 2025 v2 artifacts.

## Setup

- Training data: KNHANES 2022-2023, age >= 65, BIA-label available rows only
- Fixed model: Logistic Regression + sigmoid calibration
- Labels are not mixed: training uses only 2022-2023 `sarcopenia_bia_label`
- 2024 primary validation label: `sarcopenia_bia_label`
- 2024 auxiliary validation label: `sarcopenia_dxa_label` from `GS_SP_DXA`, if available

## Data Counts

- 2022-2023 train label-available n: 2,683
- 2024 age >= 65 n: 1,951
- 2024 BIA label-available n: 1,596
- 2024 DXA label-available n: 1,166

## 2024 BIA External Validation

| feature_set | n | positive_n | prevalence | auroc | auprc | brier | ece | calibration_intercept | calibration_slope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| self_report_full | 1596 | 143 | 0.0896 | 0.9004 | 0.5223 | 0.0580 | 0.0202 | 0.0291 | 1.1661 |
| self_report_minimal | 1596 | 143 | 0.0896 | 0.8841 | 0.4817 | 0.0609 | 0.0253 | -0.0275 | 1.1646 |

## 2024 DXA Auxiliary Validation

| feature_set | n | positive_n | prevalence | auroc | auprc | brier | ece | calibration_intercept | calibration_slope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| self_report_full | 1166 | 118 | 0.1012 | 0.7945 | 0.4213 | 0.0748 | 0.0257 | -0.3215 | 0.7404 |
| self_report_minimal | 1166 | 118 | 0.1012 | 0.7838 | 0.3793 | 0.0763 | 0.0173 | -0.3099 | 0.7897 |

## Risk Tier Interpretation

Relative tiers are safer for communication if 2024 calibration drifts. Absolute tiers can be considered only when Brier/ECE and calibration slope/intercept remain acceptable.

| year | feature_set | label | tier_scheme | risk_tier | n | positive_n | observed_prevalence | mean_predicted_probability | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2024 | self_report_full | sarcopenia_bia_label | relative | low | 798 | 5 | 0.0063 | 0.0140 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_bia_label | relative | medium | 479 | 29 | 0.0605 | 0.0809 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_bia_label | relative | high | 319 | 109 | 0.3417 | 0.3544 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_bia_label | absolute | low | 1144 | 20 | 0.0175 | 0.0288 | use_if_calibration_acceptable |
| 2024 | self_report_full | sarcopenia_bia_label | absolute | medium | 206 | 24 | 0.1165 | 0.1454 | use_if_calibration_acceptable |
| 2024 | self_report_full | sarcopenia_bia_label | absolute | high | 246 | 99 | 0.4024 | 0.4068 | use_if_calibration_acceptable |
| 2024 | self_report_full | sarcopenia_dxa_label | relative | low | 583 | 19 | 0.0326 | 0.0129 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_dxa_label | relative | medium | 350 | 30 | 0.0857 | 0.0712 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_dxa_label | relative | high | 233 | 69 | 0.2961 | 0.3227 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_full | sarcopenia_dxa_label | absolute | low | 864 | 42 | 0.0486 | 0.0280 | use_if_calibration_acceptable |
| 2024 | self_report_full | sarcopenia_dxa_label | absolute | medium | 145 | 17 | 0.1172 | 0.1446 | use_if_calibration_acceptable |
| 2024 | self_report_full | sarcopenia_dxa_label | absolute | high | 157 | 59 | 0.3758 | 0.3977 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_bia_label | relative | low | 798 | 9 | 0.0113 | 0.0195 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_bia_label | relative | medium | 479 | 32 | 0.0668 | 0.0923 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_bia_label | relative | high | 319 | 102 | 0.3197 | 0.3487 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_bia_label | absolute | low | 1097 | 18 | 0.0164 | 0.0333 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_bia_label | absolute | medium | 244 | 30 | 0.1230 | 0.1433 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_bia_label | absolute | high | 255 | 95 | 0.3725 | 0.3901 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_dxa_label | relative | low | 583 | 21 | 0.0360 | 0.0183 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_dxa_label | relative | medium | 350 | 28 | 0.0800 | 0.0830 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_dxa_label | relative | high | 233 | 69 | 0.2961 | 0.3151 | recommended_if_absolute_calibration_uncertain |
| 2024 | self_report_minimal | sarcopenia_dxa_label | absolute | low | 834 | 37 | 0.0444 | 0.0330 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_dxa_label | absolute | medium | 170 | 20 | 0.1176 | 0.1456 | use_if_calibration_acceptable |
| 2024 | self_report_minimal | sarcopenia_dxa_label | absolute | high | 162 | 61 | 0.3765 | 0.3757 | use_if_calibration_acceptable |
