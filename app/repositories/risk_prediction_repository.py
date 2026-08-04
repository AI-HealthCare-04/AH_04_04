from datetime import datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.models.health import HealthProfile
from app.models.predictions import PredictionFeedback, RiskPrediction
from app.models.users import User


class RiskPredictionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def lock_user_for_reassess(self, user_id: int) -> None:
        """재평가 트랜잭션을 사용자 단위로 직렬화한다(리뷰 P1 — 하루 1회 정책의 실제 보장).

        하루 1회 판정은 '오늘 재평가가 있나 확인 → 없으면 저장'이라 그 자체로는 check-then-insert
        경쟁에 열려 있다. 연타·재시도로 두 요청이 거의 동시에 오면 둘 다 '없음'을 읽고 각각
        프로필·예측을 저장해 정책이 깨진다.

        users 행을 FOR UPDATE 로 잠그고 이걸 **트랜잭션의 첫 읽기**로 두면
          ① 같은 사용자의 동시 재평가가 직렬화되고,
          ② locking read 이후의 consistent read 가 선행 트랜잭션 커밋 이후 스냅샷을 잡아
             뒤 요청의 [get_today_reassessment] 가 앞 요청이 저장한 예측을 반드시 본다
             (READ COMMITTED. 미션 완료 동시성과 같은 패턴 — mission_repository.lock_user_for_completion).
        별도 컬럼·마이그레이션 없이 정책을 DB 수준에서 강제할 수 있어 이 방식을 택했다.
        """
        await self.session.execute(select(User.user_id).where(User.user_id == user_id).with_for_update())

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

        재평가 여부는 `risk_predictions.is_reassessment` 로 판별한다(#408 A+3). 전에는 프로필의
        `input_method = service_log` 로 봤는데, 그러려면 **재평가마다 프로필 행을 통째로 복제**해야
        했다 — 판별 근거 하나 때문에 생년월일·성별·신체계측이 매번 복사됐다.
        온보딩 최초 예측은 이 조회에 걸리지 않아 가입 당일에도 재평가를 한 번은 쓸 수 있다.

        **자동 예측은 제외한다**(#422). 자정 배치가 만든 행까지 세면 그날의 1회를 자동이 소진해,
        사용자가 내 정보를 고치고 재평가를 눌러도 자동 예측이 그대로 반환된다 — 수정이 다음 날까지
        반영되지 않는다. 카운터를 나눠 자동 1회와 수동 1회를 각각 보장한다.
        """
        return await self._get_today_prediction(user_id, is_auto=False)

    async def get_today_auto_prediction(self, user_id: int) -> RiskPrediction | None:
        """오늘(KST) 저장된 **자동(자정 배치)** 예측 1건(#422).

        배치가 시각이 아니라 **상태**로 판단하도록 하는 조회다. "00:00 에 실행"으로 두면 그 순간
        컨테이너가 내려가 있을 때(배포·재시작) 그날 예측이 통째로 빠지고 추이에 구멍이 생긴다.
        "오늘 자동 예측이 없으면 만든다"로 두면 언제 실행되든 하루 한 점이 보장된다.
        """
        return await self._get_today_prediction(user_id, is_auto=True)

    async def _get_today_prediction(self, user_id: int, *, is_auto: bool) -> RiskPrediction | None:
        start = datetime.combine(today_kst(), time.min)
        end = start + timedelta(days=1)
        stmt = (
            select(RiskPrediction)
            .where(
                RiskPrediction.user_id == user_id,
                RiskPrediction.is_reassessment.is_(True),
                RiskPrediction.is_auto.is_(is_auto),
                RiskPrediction.created_at >= start,
                RiskPrediction.created_at < end,
            )
            .order_by(RiskPrediction.created_at.desc(), RiskPrediction.prediction_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

    async def get_user_ids_needing_auto_prediction(self) -> list[int]:
        """자정 배치 대상 — 건강 프로필이 있고 **오늘 자동 예측이 아직 없는** 사용자(#422).

        조건을 `onboarding_status = completed` 가 아니라 **프로필 보유**로 잡는다. 그 상태값은
        최초 예측이 성공해야 붙는데(complete_onboarding), 정작 예측에 필요한 것은 프로필뿐이다.
        서비스(`create_auto_prediction`)도 프로필이 없으면 그냥 건너뛰므로 여기서 같은 기준을 쓴다.

        탈퇴자(`deleted_at`)는 제외한다. 만 65세 미만은 여기서 거르지 않는다 — 나이 판정은
        프로필을 읽어야 하고 서비스가 이미 `AgeNotSupportedError` 로 건너뛰므로, 조회를 단순하게
        두고 판정은 한 곳에서만 한다.
        """
        start = datetime.combine(today_kst(), time.min)
        end = start + timedelta(days=1)
        today_auto = (
            select(RiskPrediction.user_id)
            .where(
                RiskPrediction.is_auto.is_(True),
                RiskPrediction.created_at >= start,
                RiskPrediction.created_at < end,
            )
            .scalar_subquery()
        )
        has_profile = select(HealthProfile.user_id).where(HealthProfile.user_id == User.user_id).exists()
        stmt = (
            select(User.user_id)
            .where(
                User.deleted_at.is_(None),
                has_profile,
                User.user_id.not_in(today_auto),
            )
            .order_by(User.user_id)
        )
        return list((await self.session.scalars(stmt)).all())

    async def get_recent_predictions(self, user_id: int, limit: int) -> list[RiskPrediction]:
        stmt = (
            select(RiskPrediction)
            .where(RiskPrediction.user_id == user_id)
            .order_by(RiskPrediction.created_at.desc(), RiskPrediction.prediction_id.desc())
            .limit(limit)
        )
        result = await self.session.scalars(stmt)
        return list(result)
