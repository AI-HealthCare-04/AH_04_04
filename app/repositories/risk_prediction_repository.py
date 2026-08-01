from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.predictions import PredictionFeedback, RiskPrediction


class RiskPredictionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
        self.session.add(prediction)
        await self.session.flush()
        return prediction

    async def get_prediction_for_user(self, prediction_id: int, user_id: int) -> RiskPrediction | None:
        # user_id 조건이 소유권 검증을 겸한다: 남의 예측이면 없는 것과 동일하게 None(→404).
        stmt = select(RiskPrediction).where(
            RiskPrediction.prediction_id == prediction_id,
            RiskPrediction.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def get_feedback(self, prediction_id: int) -> PredictionFeedback | None:
        stmt = select(PredictionFeedback).where(PredictionFeedback.prediction_id == prediction_id)
        return await self.session.scalar(stmt)

    async def add_feedback(self, feedback: PredictionFeedback) -> PredictionFeedback:
        self.session.add(feedback)
        await self.session.flush()
        return feedback

    async def get_latest_prediction(self, user_id: int) -> RiskPrediction | None:
        stmt = (
            select(RiskPrediction)
            .where(RiskPrediction.user_id == user_id)
            .order_by(RiskPrediction.created_at.desc(), RiskPrediction.prediction_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

    async def get_recent_predictions(self, user_id: int, limit: int) -> list[RiskPrediction]:
        stmt = (
            select(RiskPrediction)
            .where(RiskPrediction.user_id == user_id)
            .order_by(RiskPrediction.created_at.desc(), RiskPrediction.prediction_id.desc())
            .limit(limit)
        )
        result = await self.session.scalars(stmt)
        return list(result)
