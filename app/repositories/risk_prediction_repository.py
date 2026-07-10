from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.predictions import RiskPrediction


class RiskPredictionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
        self.session.add(prediction)
        await self.session.flush()
        return prediction

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
