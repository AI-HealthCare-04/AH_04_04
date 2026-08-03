from datetime import datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.models.enums import InputMethod
from app.models.health import HealthProfile
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

    async def get_today_reassessment(self, user_id: int) -> RiskPrediction | None:
        """오늘(KST) 저장된 **재평가** 예측 1건. 하루 1회 정책(#388)의 멱등 판정에 쓴다.

        재평가 여부는 별도 컬럼 없이 **프로필의 input_method 로 판별**한다 — 재평가는 항상
        `_create_reassessment_profile` 이 만든 SERVICE_LOG 프로필을 쓰고(온보딩 최초 예측은 FORM),
        이 구분만으로 충분해서 마이그레이션 없이 정책을 강제할 수 있다.
        온보딩 당일에도 재평가를 한 번은 쓸 수 있게, 최초 예측은 이 조회에 걸리지 않는다.
        """
        start = datetime.combine(today_kst(), time.min)
        end = start + timedelta(days=1)
        stmt = (
            select(RiskPrediction)
            .join(HealthProfile, HealthProfile.profile_id == RiskPrediction.profile_id)
            .where(
                RiskPrediction.user_id == user_id,
                HealthProfile.input_method == InputMethod.SERVICE_LOG,
                RiskPrediction.created_at >= start,
                RiskPrediction.created_at < end,
            )
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
