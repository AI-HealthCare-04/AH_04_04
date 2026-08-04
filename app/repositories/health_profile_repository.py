from collections.abc import Iterable
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import to_kst_date
from app.models.enums import ActivityInputSource
from app.models.health import HealthCheckSession, HealthProfile


class HealthProfileRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_session(self, session_id: int, user_id: int) -> HealthCheckSession | None:
        stmt = select(HealthCheckSession).where(
            HealthCheckSession.session_id == session_id,
            HealthCheckSession.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def get_onboarding_completed_on(self, user_id: int) -> date | None:
        """온보딩을 마친 날(KST). 활동 일수 반영 시작일(8일차) 판정의 기준점이다(#422).

        가입일(`users.created_at`)이 아니라 이 날을 쓴다. User 행은 **소셜 로그인 시점에**
        `onboarding_status=pending` 으로 먼저 만들어져서, 약관 화면에서 이탈했다가 며칠 뒤
        돌아와 완주한 사용자는 가입일과 실제 사용 시작일이 벌어진다. 그 사용자에게 가입일 기준
        8일차를 적용하면 프로필도 없던 기간까지 활동 창에 들어와 활동 일수가 0 에 가깝게 잡히고
        점수가 떨어진다 — 이 기능이 막으려던 바로 그 현상이다.

        완료 세션이 여럿이면 **가장 이른 것**을 쓴다(재측정 등으로 세션이 늘어도 시작일은 안 밀린다).
        """
        stmt = select(func.min(HealthCheckSession.completed_at)).where(
            HealthCheckSession.user_id == user_id,
            HealthCheckSession.completed_at.is_not(None),
        )
        completed_at = await self.session.scalar(stmt)
        return to_kst_date(completed_at) if completed_at is not None else None

    async def create_profile(self, profile: HealthProfile) -> HealthProfile:
        self.session.add(profile)
        await self.session.flush()
        return profile

    async def get_profile(self, profile_id: int, user_id: int) -> HealthProfile | None:
        stmt = select(HealthProfile).where(
            HealthProfile.profile_id == profile_id,
            HealthProfile.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def get_profiles_by_ids(self, profile_ids: Iterable[int], user_id: int) -> dict[int, HealthProfile]:
        """추이 응답이 신체 정보 변경 여부를 판정할 때 쓰는 일괄 조회(#389 C).

        예측마다 한 건씩 읽으면 N+1 이라 이력 길이에 비례해 쿼리가 늘어난다. user_id 로 함께
        걸러 다른 사용자의 프로필이 섞이지 않게 한다.
        """
        ids = list(dict.fromkeys(profile_ids))
        if not ids:
            return {}
        stmt = select(HealthProfile).where(
            HealthProfile.profile_id.in_(ids),
            HealthProfile.user_id == user_id,
        )
        rows = (await self.session.scalars(stmt)).all()
        return {row.profile_id: row for row in rows}

    async def get_latest_profile(self, user_id: int) -> HealthProfile | None:
        stmt = (
            select(HealthProfile)
            .where(
                HealthProfile.user_id == user_id,
                HealthProfile.activity_input_source != ActivityInputSource.SERVICE_LOG,
            )
            .order_by(HealthProfile.created_at.desc(), HealthProfile.profile_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)
