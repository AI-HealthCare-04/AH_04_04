from dataclasses import dataclass


@dataclass(frozen=True)
class RiskPredictionResult:
    care_stage: str
    display_message: str
    model_variant: str = "rule_based_scaffold"


class RiskPredictor:
    async def predict(self, features: dict) -> RiskPredictionResult:
        # TODO: Load the trained KNHANES artifact and run inference in an executor.
        return RiskPredictionResult(
            care_stage="maintain",
            display_message="지금처럼 꾸준히 실천해보세요.",
        )
