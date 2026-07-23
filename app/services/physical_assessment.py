from datetime import date
from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import now_kst
from app.dtos.physical_assessment import (
    PhysicalAssessmentActivityProfile,
    PhysicalAssessmentCreateRequest,
    PhysicalAssessmentResponse,
)
from app.models.activity import ActivityLevelChangeLog, UserActivityProfile
from app.models.enums import ActivityLevel, HealthCheckStatus, LevelReason, ReasonType
from app.models.health import HealthCheckSession, PhysicalAssessment
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.health_check_repository import HealthCheckRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.physical_assessment_repository import PhysicalAssessmentRepository

# 콜드스타트 밴드는 5STS(5회 의자 일어서기) '단독'으로 산출한다(팀 결정 2026-07-20).
#   경계 = 연령대 5STS 평균(초, Bohannon 2006): 5STS ≤ 평균 → 중 / 초과 → 하. 콜드스타트는 하/중만.
#   ⚠️ 6m 걷기는 미구현·제외(#109). 상(hard)은 행동 데이터로만 획득.
# Bohannon 2006 규준: 11.4=60-69 / 12.6=70-79 / 14.8=80-89. (앱 대상 65+ → 첫 구간은 65-69에 적용=부분집합)
#   90+ 는 규준 범위 밖이라 외삽하지 않고 밴드 미산출(→ 하). 출처: pubmed.ncbi.nlm.nih.gov/17037663
NORM_5STS_65_69 = Decimal("11.4")
NORM_5STS_70_79 = Decimal("12.6")
NORM_5STS_80_89 = Decimal("14.8")


class PhysicalAssessmentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PhysicalAssessmentRepository(session)
        self.activity_repo = ActivityProfileRepository(session)
        self.health_repo = HealthProfileRepository(session)
        self.health_check_repo = HealthCheckRepository(session)

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        health_check_session = await self._get_started_session(data.session_id, user.user_id)

        # 밴드는 5STS 단독으로 '항상' 산출: 유효 5STS → 중/하, 미실시/스킵/통증/어지럼/연령미상 → 하.
        #   팀 결정 "미실시·중단 → 하(기본값)"에 따라 기존 레벨도 하로 수렴한다(리뷰 #103-1).
        chair_stand_valid = not data.chair_stand_skipped and data.chair_stand_5_time_sec is not None
        profile = await self.health_repo.get_latest_profile(user.user_id)
        age = self._age_years(profile.birth_date) if profile is not None else None
        activity_level = self._determine_activity_level(
            chair_stand_sec=data.chair_stand_5_time_sec if chair_stand_valid else None,
            age_norm_sec=self._age_norm_5sts(age),
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
        )

        assessment = PhysicalAssessment(
            user_id=user.user_id,
            session_id=data.session_id,
            assessment_type=data.assessment_type,
            chair_stand_5_time_sec=data.chair_stand_5_time_sec,
            chair_stand_skipped=data.chair_stand_skipped,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
            used_for_level_setting=True,
        )
        await self.repo.create_physical_assessment(assessment)
        activity_profile = await self._upsert_activity_profile(
            user_id=user.user_id,
            current_level=activity_level,
            physical_assessment_id=assessment.physical_assessment_id,
        )
        if health_check_session is not None:
            health_check_session.status = HealthCheckStatus.COMPLETED
            health_check_session.completed_at = now_kst()
            await self.health_check_repo.update_session(health_check_session)
        await self.session.commit()
        await self.session.refresh(assessment)
        await self.session.refresh(activity_profile)
        return PhysicalAssessmentResponse(
            physical_assessment_id=assessment.physical_assessment_id,
            used_for_level_setting=assessment.used_for_level_setting,
            activity_profile=PhysicalAssessmentActivityProfile(
                current_level=activity_profile.current_level,
                level_reason=activity_profile.level_reason,
            ),
        )

    async def _get_started_session(self, session_id: int | None, user_id: int) -> HealthCheckSession | None:
        if session_id is None:
            return None
        health_check_session = await self.health_check_repo.get_session(session_id, user_id)
        if health_check_session is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health check session not found.")
        if health_check_session.status != HealthCheckStatus.STARTED:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Health check session is already finished.")
        return health_check_session

    @staticmethod
    def _age_years(birth: date) -> int:
        today = now_kst().date()
        return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))

    @staticmethod
    def _age_norm_5sts(age: int | None) -> Decimal | None:
        """연령대 5STS 평균(초, Bohannon 2006: 65-69→11.4 / 70-79→12.6 / 80-89→14.8).
        규준·앱대상(65+) 범위 밖(미상·<65·90+)과 비정상 연령(미래 생년 등 음수)은 외삽하지 않고
        None → 안전 기본값(하)으로 수렴시킨다(리뷰 #103). 65-69에는 규준 60-69(부분집합)를 적용.

        ⚠️ 65세 미만에 '젊은 연령대 규준'을 새로 잡지 말 것 — 의도된 동작이지 버그가 아니다(#155).
           근거는 두 가지다: (1) 이 앱의 지원 대상이 65세 이상이고, (2) 밴드 경계로 쓰는 5STS 규준
           (Bohannon 2006)이 65-89 만 있어 그 밖(<65·90+)은 외삽하지 않는다. 두 이유 모두로 <65 는
           규준 미산출(→하)이 맞다.
           그 결과 65세 미만(예: 40세, 매우 빠른 5STS)도 '하'로 수렴하는데, 이는 성능 역전이 아니라
           '지원 대상 밖 기본값'이다 — 규준을 확장하는 대신 온보딩에서 대상 연령을 안내하는 게 맞다.

           참고: 위험도 추론도 65세 미만에는 적용하지 않는 방향이 논의됐으나, 현재 예측 서비스에는
           연령 가드가 없다(RiskPredictionService 는 <65 도 그대로 예측한다). 그 정책은 예측 API 에
           연령 가드·응답 계약·테스트가 별도로 구현될 때 성립하며, 이 주석은 그 사실을 전제하지 않는다."""
        # 하한/상한 fail-safe: 미상·<65·90+·음수(미래 생년)는 모두 밴드 미산출(→하).
        #   DTO가 미래 생년을 걸러도 기존/비정상 데이터가 들어올 수 있어 서비스에서 다시 막는다.
        if age is None or age < 65 or age >= 90:
            return None
        if age < 70:
            return NORM_5STS_65_69
        if age < 80:
            return NORM_5STS_70_79
        return NORM_5STS_80_89  # 80-89

    @staticmethod
    def _determine_activity_level(
        *,
        chair_stand_sec: Decimal | None,
        age_norm_sec: Decimal | None,
        pain_reported: bool,
        dizziness_reported: bool,
    ) -> ActivityLevel:
        # 콜드스타트 밴드는 하/중만(상은 행동 데이터로 획득). 6m 걷기는 밴드에 쓰지 않는다.
        #   안전 플래그(통증·어지럼)·측정값/연령 기준 부재 → 하(기본).
        if pain_reported or dizziness_reported or chair_stand_sec is None or age_norm_sec is None:
            return ActivityLevel.EASY
        # 5STS ≤ 연령대 평균 → 중 / 초과 → 하
        return ActivityLevel.NORMAL if chair_stand_sec <= age_norm_sec else ActivityLevel.EASY

    async def _upsert_activity_profile(
        self,
        *,
        user_id: int,
        current_level: ActivityLevel,
        physical_assessment_id: int,
    ) -> UserActivityProfile:
        profile = await self.activity_repo.get_by_user_id(user_id)
        if profile is None:
            profile = UserActivityProfile(
                user_id=user_id,
                current_level=current_level,
                level_reason=LevelReason.INITIAL_TEST,
                physical_assessment_id=physical_assessment_id,
                started_at=now_kst(),
            )
            await self.activity_repo.create_profile(profile)
            return profile

        from_level = profile.current_level
        profile.current_level = current_level
        profile.level_reason = LevelReason.RULE
        profile.physical_assessment_id = physical_assessment_id
        profile.started_at = now_kst()
        await self.activity_repo.update_profile(profile)
        if from_level != current_level:
            await self.activity_repo.create_level_change_log(
                ActivityLevelChangeLog(
                    user_id=user_id,
                    from_level=from_level,
                    to_level=current_level,
                    reason_type=ReasonType.RULE,
                    reason_text=f"physical_assessment:{physical_assessment_id}",
                    accepted_by_user=False,
                )
            )
        return profile
