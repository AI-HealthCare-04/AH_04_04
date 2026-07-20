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

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        # 6m 걷기는 밴드 미사용 확장/기록용. 시간이 있어야 유효 기록으로 저장하고,
        #   시간이 없으면 스킵으로 정규화 → "스킵 아닌데 값 없음" 모순 상태를 안 남긴다(리뷰 #103-2).
        walk_provided = data.walk_6m_time_sec is not None
        walk_distance = data.walk_6m_distance_m if walk_provided else None
        if walk_provided and walk_distance is None:
            walk_distance = DEFAULT_WALK_DISTANCE_M
        walk_speed = self._calculate_walk_speed(walk_distance, data.walk_6m_time_sec)
        walk_skipped_stored = data.walk_6m_skipped or not walk_provided

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
            walk_6m_time_sec=data.walk_6m_time_sec,
            walk_6m_distance_m=walk_distance,
            walk_6m_speed_mps=walk_speed,
            walk_6m_skipped=walk_skipped_stored,
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
        """연령대 5STS 평균(초, Bohannon 2006). 규준 범위(60-89) 밖인 90+는 외삽 없이 None(→하).
        앱 대상 65+; 65-69에는 규준 60-69(부분집합)를 적용한다."""
        if age is None:
            return None
        if age < 70:
            return NORM_5STS_65_69
        if age < 80:
            return NORM_5STS_70_79
        if age < 90:
            return NORM_5STS_80_89
        return None  # 90+ 규준 범위 밖 → 안전 기본값(하)

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
