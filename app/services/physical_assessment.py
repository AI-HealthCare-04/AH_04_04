from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import now_kst
from app.dtos.physical_assessment import (
    PhysicalAssessmentActivityProfile,
    PhysicalAssessmentCreateRequest,
    PhysicalAssessmentResponse,
)
from app.models.activity import ActivityLevelChangeLog, UserActivityProfile
from app.models.enums import ActivityLevel, LevelReason, ReasonType
from app.models.health import PhysicalAssessment
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.physical_assessment_repository import PhysicalAssessmentRepository

DEFAULT_WALK_DISTANCE_M = Decimal("6.00")

# 콜드스타트 밴드는 5STS(5회 의자 일어서기) '단독'으로 산출한다(팀 결정 2026-07-20).
#   경계 = 연령대 5STS 평균(초, Bohannon 2006): 5STS ≤ 평균 → 중 / 초과 → 하. 콜드스타트는 하/중만.
#   ⚠️ 6m 걷기 속도는 밴드에 쓰지 않는다(확장/기록·본인 비교용). 상(hard)은 행동 데이터로만 획득.
NORM_5STS_65_69 = Decimal("11.4")
NORM_5STS_70_79 = Decimal("12.6")
NORM_5STS_80_PLUS = Decimal("14.8")


class PhysicalAssessmentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PhysicalAssessmentRepository(session)
        self.activity_repo = ActivityProfileRepository(session)
        self.health_repo = HealthProfileRepository(session)

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        walk_distance = data.walk_6m_distance_m
        if data.walk_6m_time_sec is not None and walk_distance is None:
            walk_distance = DEFAULT_WALK_DISTANCE_M

        # 6m 걷기는 기록·본인 비교(확장)용으로만 속도 계산/저장 — 밴드 산출엔 쓰지 않는다.
        walk_speed = self._calculate_walk_speed(walk_distance, data.walk_6m_time_sec)

        # 밴드는 5STS 단독. 유효한 5STS(미스킵·값 존재)가 있어야 이 평가가 레벨을 설정한다.
        #   미실시/스킵 → used_for_level_setting=False → 기존 유지 또는 기본값(하) 생성.
        chair_stand_valid = not data.chair_stand_skipped and data.chair_stand_5_time_sec is not None
        used_for_level_setting = chair_stand_valid

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
            walk_6m_time_sec=data.walk_6m_time_sec,
            walk_6m_distance_m=walk_distance,
            walk_6m_speed_mps=walk_speed,
            walk_6m_skipped=data.walk_6m_skipped,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
            used_for_level_setting=used_for_level_setting,
        )
        await self.repo.create_physical_assessment(assessment)
        if used_for_level_setting:
            activity_profile = await self._upsert_activity_profile(
                user_id=user.user_id,
                current_level=activity_level,
                physical_assessment_id=assessment.physical_assessment_id,
            )
        else:
            # Walk measurement was skipped, so keep the existing level. If none exists, create the default.
            activity_profile = await self._get_or_create_default_profile(user.user_id)
        await self.session.commit()
        await self.session.refresh(assessment)
        await self.session.refresh(activity_profile)
        return PhysicalAssessmentResponse(
            physical_assessment_id=assessment.physical_assessment_id,
            walk_6m_speed_mps=assessment.walk_6m_speed_mps,
            used_for_level_setting=assessment.used_for_level_setting,
            activity_profile=PhysicalAssessmentActivityProfile(
                current_level=activity_profile.current_level,
                level_reason=activity_profile.level_reason,
            ),
        )

    @staticmethod
    def _calculate_walk_speed(distance_m: Decimal | None, time_sec: Decimal | None) -> Decimal | None:
        if distance_m is None or time_sec is None:
            return None
        return (distance_m / time_sec).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    @staticmethod
    def _age_years(birth: date) -> int:
        today = now_kst().date()
        return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))

    @staticmethod
    def _age_norm_5sts(age: int | None) -> Decimal | None:
        """연령대 5STS 평균(초). 앱 대상 65+; <65도 방어적으로 최엄격(65-69) 기준."""
        if age is None:
            return None
        if age < 70:
            return NORM_5STS_65_69
        if age < 80:
            return NORM_5STS_70_79
        return NORM_5STS_80_PLUS

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

    async def _get_or_create_default_profile(self, user_id: int) -> UserActivityProfile:
        profile = await self.activity_repo.get_by_user_id(user_id)
        if profile is not None:
            return profile
        profile = UserActivityProfile(
            user_id=user_id,
            current_level=ActivityLevel.EASY,
            level_reason=LevelReason.RULE,
            physical_assessment_id=None,
            started_at=now_kst(),
        )
        await self.activity_repo.create_profile(profile)
        return profile
