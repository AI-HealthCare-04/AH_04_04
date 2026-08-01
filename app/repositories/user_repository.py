import uuid
from datetime import datetime

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.models.activity import ActivityLevelChangeLog, UserActivityProfile
from app.models.analytics import StsOverlayEvent
from app.models.dashboard import DailyActivitySummary
from app.models.health import HealthCheckSession, HealthProfile, PhysicalAssessment
from app.models.missions import GameLog, MealLog, MissionLog, PhysicalActivityLog, SensorSession
from app.models.predictions import RiskPrediction
from app.models.settings import PersonalizedSetting
from app.models.terms import TermsAgreement
from app.models.users import AuthProvider, OnboardingStatus, User


class UserRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_user(self, user_id: int) -> User | None:
        # 탈퇴(soft-delete)된 사용자는 조회되지 않는다 → 인증 경로에서 자동으로 401 처리된다.
        stmt = select(User).where(User.user_id == user_id, User.deleted_at.is_(None))
        return await self.session.scalar(stmt)

    async def get_by_provider_social_id(self, provider: AuthProvider, social_id: str) -> User | None:
        stmt = select(User).where(
            User.provider == provider,
            User.social_id == social_id,
            User.deleted_at.is_(None),
        )
        return await self.session.scalar(stmt)

    # 소셜 로그인(google/kakao) 사용자 생성. provider만 다르고 흐름은 동일하므로 공통 메서드로 둡니다.
    async def create_social_user(self, provider: AuthProvider, social_id: str, nickname: str) -> User:
        user = User(
            provider=provider,
            social_id=social_id,
            nickname=nickname,
            onboarding_status=OnboardingStatus.PENDING,
            last_login_at=datetime.now(config.TIMEZONE),
        )
        self.session.add(user)
        await self.session.flush()
        return user

    # 게스트(체험하기) 사용자 생성.
    # 개인정보는 받지 않지만, UNIQUE(provider, social_id) 구조를 지키기 위해
    # 서버가 매 호출마다 비식별 랜덤 ID("guest:<uuid>")를 social_id로 생성합니다.
    # → 호출마다 새 게스트가 생기므로 여러 명이 동시에 체험해도 데이터가 섞이지 않습니다.
    async def create_guest_user(self, nickname: str) -> User:
        social_id = f"guest:{uuid.uuid4().hex}"
        return await self.create_social_user(AuthProvider.GUEST, social_id, nickname)

    async def update_last_login(self, user: User) -> None:
        user.last_login_at = datetime.now(config.TIMEZONE)
        await self.session.flush()

    async def update_nickname(self, user: User, nickname: str) -> User:
        user.nickname = nickname
        await self.session.flush()
        return user

    async def soft_delete(self, user: User) -> None:
        """탈퇴 처리(#356): 개인 식별자 익명화 + deleted_at 기록.

        social_id 를 `deleted:<uuid4>` 로 치환해 UNIQUE(provider, social_id) 를 비운다 —
        같은 소셜 계정으로 다시 로그인하면 **복구가 아니라 신규 가입**이 된다(옵션 2, 팀 결정).
        원본 social_id·닉네임은 남기지 않는다(개인정보 최소화). users 행 자체는 남겨
        탈퇴 사실과 시점은 보존한다. 연결 데이터 파기는 [purge_user_data] 가 담당한다.
        """
        user.social_id = f"deleted:{uuid.uuid4().hex}"  # VARCHAR(100) 내(40자)
        user.nickname = "탈퇴한 사용자"
        user.deleted_at = datetime.now(config.TIMEZONE)
        await self.session.flush()

    async def purge_user_data(self, user_id: int) -> None:
        """탈퇴 사용자의 연결 데이터를 실제 삭제한다(#356 — 리뷰 반영: 익명화 ≠ 파기).

        건강 프로필·신체평가·예측·미션 기록·약관 동의 등 user_id 를 직·간접 참조하는 모든 행이
        대상이다. FK 제약 때문에 **자식 → 부모 순서**로 지운다(ON DELETE CASCADE 미설정).
        mission_logs 를 경유하는 상세 로그(식사·활동·게임·센서)는 user_id 컬럼이 없어 서브쿼리로 건다.

        ⚠️ 테이블이 새로 추가되면 여기에 함께 등록해야 한다 —
           `test_withdrawal.py` 가 메타데이터를 순회해 누락을 잡는다(잔존 행 0 검증).
        """
        mission_log_ids = select(MissionLog.mission_log_id).where(MissionLog.user_id == user_id)
        # 1) mission_logs 자식(상세 로그) — user_id 컬럼이 없어 부모 id 서브쿼리로 건다.
        await self.session.execute(delete(GameLog).where(GameLog.mission_log_id.in_(mission_log_ids)))
        await self.session.execute(delete(MealLog).where(MealLog.mission_log_id.in_(mission_log_ids)))
        await self.session.execute(
            delete(PhysicalActivityLog).where(PhysicalActivityLog.mission_log_id.in_(mission_log_ids))
        )
        await self.session.execute(delete(SensorSession).where(SensorSession.mission_log_id.in_(mission_log_ids)))
        # 2) health_profiles·physical_assessments 를 무는 자식
        await self.session.execute(delete(RiskPrediction).where(RiskPrediction.user_id == user_id))
        await self.session.execute(delete(UserActivityProfile).where(UserActivityProfile.user_id == user_id))
        await self.session.execute(delete(ActivityLevelChangeLog).where(ActivityLevelChangeLog.user_id == user_id))
        # 3) health_check_sessions 자식 → 부모 순
        await self.session.execute(delete(PhysicalAssessment).where(PhysicalAssessment.user_id == user_id))
        await self.session.execute(delete(HealthProfile).where(HealthProfile.user_id == user_id))
        await self.session.execute(delete(MissionLog).where(MissionLog.user_id == user_id))
        await self.session.execute(delete(HealthCheckSession).where(HealthCheckSession.user_id == user_id))
        # 4) users 직속 나머지
        await self.session.execute(delete(TermsAgreement).where(TermsAgreement.user_id == user_id))
        await self.session.execute(delete(DailyActivitySummary).where(DailyActivitySummary.user_id == user_id))
        await self.session.execute(delete(StsOverlayEvent).where(StsOverlayEvent.user_id == user_id))
        await self.session.execute(delete(PersonalizedSetting).where(PersonalizedSetting.user_id == user_id))
        await self.session.flush()
