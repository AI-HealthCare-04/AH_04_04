from decimal import ROUND_HALF_UP, Decimal

from fastapi import HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import now_kst
from app.dtos.physical_assessment import (
    PhysicalAssessmentCreateRequest,
    PhysicalAssessmentHistoryItem,
    PhysicalAssessmentHistoryResponse,
    PhysicalAssessmentResponse,
)
from app.models.enums import HealthCheckStatus
from app.models.health import HealthCheckSession, PhysicalAssessment
from app.models.users import User
from app.repositories.health_check_repository import HealthCheckRepository
from app.repositories.physical_assessment_repository import PhysicalAssessmentRepository

# 난이도 폐기(#428, 2026-08-05): 5STS 는 측정 기록·추이(#353)·안전망 카드 입력으로만 쓴다.
#   연령 규준(Bohannon 2006) 기반 활동수준 산출과 user_activity_profiles 갱신은 제거했다.


class PhysicalAssessmentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PhysicalAssessmentRepository(session)
        self.health_check_repo = HealthCheckRepository(session)

    async def get_measured_history(self, user: User, limit: int) -> PhysicalAssessmentHistoryResponse:
        """5STS 측정 이력(최신순, #353). 추이 표시는 앱이 시간 역순 그대로 그린다(낮을수록 좋음 표기는 앱 몫)."""
        rows = await self.repo.get_measured_history(user.user_id, limit)
        return PhysicalAssessmentHistoryResponse(assessments=[self._to_history_item(row) for row in rows])

    @staticmethod
    def _to_history_item(assessment: PhysicalAssessment) -> PhysicalAssessmentHistoryItem:
        # 이력은 측정 기록만 조회하므로(chair_stand IS NOT NULL) 시간 값이 항상 존재한다.
        return PhysicalAssessmentHistoryItem(
            physical_assessment_id=assessment.physical_assessment_id,
            assessment_type=assessment.assessment_type,
            chair_stand_5_time_sec=float(assessment.chair_stand_5_time_sec or 0),
            created_at=assessment.created_at,
        )

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        # 세션 상태별 멱등/복구 분기(#180, 미션 create_mission_log 패턴 미러링):
        #   None(독립 제출) → 세션 전이 없이 진행 · STARTED → 정상 생성+완료
        #   COMPLETED(응답 유실 재시도) → 기존 결과 200 반환 · SKIPPED → 409 · 세션 없음 → 404
        health_check_session: HealthCheckSession | None = None
        if data.session_id is not None:
            # STARTED→COMPLETED 전이가 있는 경로이므로 세션 행을 잠가 동시 건너뛰기와 직렬화한다(#207 리뷰):
            #   먼저 커밋한 쪽이 상태를 확정하고, 뒤늦은 쪽은 갱신된 상태(COMPLETED/SKIPPED)를 보고 분기한다.
            health_check_session = await self.health_check_repo.get_session(
                data.session_id, user.user_id, for_update=True
            )
            if health_check_session is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="세션을 찾을 수 없습니다.")
            if health_check_session.status == HealthCheckStatus.COMPLETED:
                return await self._resolve_completed_resubmit(data.session_id, data, user.user_id)
            if health_check_session.status == HealthCheckStatus.SKIPPED:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="건너뛴 세션에는 체력검사를 제출할 수 없습니다.",
                )
            # 여기 도달 = STARTED

        assessment = PhysicalAssessment(
            user_id=user.user_id,
            session_id=data.session_id,
            assessment_type=data.assessment_type,
            chair_stand_5_time_sec=data.chair_stand_5_time_sec,
            chair_stand_skipped=data.chair_stand_skipped,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
            # 난이도 폐기(#428) 이후 저장분은 레벨 설정에 쓰이지 않는다(컬럼은 과거 데이터 사실 기록용으로 유지).
            used_for_level_setting=False,
        )
        try:
            await self.repo.create_physical_assessment(assessment)
            if health_check_session is not None:
                health_check_session.status = HealthCheckStatus.COMPLETED
                health_check_session.completed_at = now_kst()
                await self.health_check_repo.update_session(health_check_session)
            await self.session.commit()
        except IntegrityError:
            # 조회~삽입 사이 동시 제출(재전송 2건 동시 도착). 세션당 1건 유니크가 둘째를 막으므로
            #   롤백 후 먼저 커밋된 기존 결과를 돌려준다(미션 create_mission_log 와 동일 경합 처리).
            await self.session.rollback()
            if data.session_id is None:
                raise  # 독립 제출은 세션 유니크 대상이 아님 → 진짜 무결성 오류(삼키지 않음)
            return await self._resolve_completed_resubmit(data.session_id, data, user.user_id)
        await self.session.refresh(assessment)
        return PhysicalAssessmentResponse(
            physical_assessment_id=assessment.physical_assessment_id,
        )

    async def _resolve_completed_resubmit(
        self, session_id: int, data: PhysicalAssessmentCreateRequest, user_id: int
    ) -> PhysicalAssessmentResponse:
        """이미 COMPLETED 된 세션에 다시 들어온 제출 처리(#180 완료기준, 리뷰 #207 2b):
        동일 payload 재시도는 기존 결과로 수렴(응답 유실 재전송 멱등),
        다른 payload 는 이미 확정된 결과를 덮어쓰지 않도록 409(충돌)로 막는다.
        session_id 는 호출부에서 non-None 으로 좁혀진 값을 명시로 받는다."""
        assessment = await self.repo.get_by_session(session_id, user_id)
        if assessment is None:
            # COMPLETED 인데 기록이 없으면 데이터 이상 — 멱등으로 오인하지 않고 409 로 명확히.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="완료된 세션의 체력검사 기록을 찾을 수 없습니다.",
            )
        if not self._same_payload(data, assessment):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="이미 완료된 세션에 다른 내용의 체력검사를 다시 제출할 수 없습니다.",
            )
        return PhysicalAssessmentResponse(
            physical_assessment_id=assessment.physical_assessment_id,
        )

    @staticmethod
    def _same_payload(data: PhysicalAssessmentCreateRequest, assessment: PhysicalAssessment) -> bool:
        """재제출이 기존 저장과 동일 요청인지(멱등 판정, #207 2b). 저장에 반영되는 필드만 비교한다.
        5STS 시간은 컬럼 정밀도(Numeric(5,2))와 같은 2자리로 정규화해 같은 값의 재전송이
        부동 자릿수 차이로 '다름'이 되는 오탐을 막는다."""
        return (
            data.assessment_type == assessment.assessment_type
            and PhysicalAssessmentService._quantize_5sts(data.chair_stand_5_time_sec)
            == PhysicalAssessmentService._quantize_5sts(assessment.chair_stand_5_time_sec)
            and data.chair_stand_skipped == assessment.chair_stand_skipped
            and data.pain_reported == assessment.pain_reported
            and data.dizziness_reported == assessment.dizziness_reported
        )

    @staticmethod
    def _quantize_5sts(value: Decimal | None) -> Decimal | None:
        """5STS 시간을 저장 컬럼과 같은 소수 2자리(HALF_UP)로 정규화. None 은 그대로."""
        if value is None:
            return None
        return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
