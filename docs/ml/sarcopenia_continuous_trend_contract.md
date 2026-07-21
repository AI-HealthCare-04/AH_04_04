# Sarcopenia Continuous Trend API Contract

## Purpose

The product exposes the model's continuous sarcopenia screening score and its change over time. The client may label
the value with user-friendly language such as "muscle-health management need," but the API contract remains explicit
that the source is a calibrated model score and not a diagnosis.

## Public score

- `risk_score`: continuous model output from `0.0` to `1.0`
- Client display: multiply by 100 when presenting a percentage or a 0–100 management-need value
- `change_percentage_points`: `(current risk_score - previous risk_score) * 100`, rounded to one decimal place
  - negative: predicted score decreased
  - positive: predicted score increased
  - `null`: no valid comparison baseline

The API does not expose `internal_risk_level`, `model_version`, or `model_variant` in history items.

## Comparison policy

History is returned from oldest to newest within the requested limit.

| `comparison_status` | meaning | `change_percentage_points` |
| --- | --- | --- |
| `baseline` | first visible item in the returned series | `null` |
| `comparable` | immediately previous item uses the same model version | numeric |
| `model_changed` | immediately previous item uses a different model version | `null` |

The server owns this policy. Android must not parse or compare model-version strings. AWGS 2019 v1 and AWGS 2025 v2
scores are never connected as a health change; the first v2 record after v1 establishes a new baseline.

When `comparison_status` is `model_changed`, the client must not draw a line from the previous point. It starts a new
visual segment and baseline so scores produced by different model versions are never presented as one continuous trend.

## Compatibility and removal plan

`care_stage` remains in responses temporarily because the current Android flow consumes it. It is not the final trend
contract. After Android switches to the continuous chart, `risk_level`, `care_stage`, and `selected_threshold`
dependencies should be removed or restricted to internal compatibility paths.

The history endpoint now returns records from oldest to newest for chart consumption. Until the Android record screen
is updated, this also changes the visible order of its existing timeline because that screen renders the server order
without sorting. Coordinate the merge and deployment order with the Android trend-chart change.

## Terms and user communication

The draft service terms, privacy policy, and sensitive-health consent are updated in the same PR to disclose the
continuous score and change-over-time presentation. The user-facing screen must still state that the result is a
screening reference, not a medical diagnosis, and must avoid definitive wording such as "you have sarcopenia."
