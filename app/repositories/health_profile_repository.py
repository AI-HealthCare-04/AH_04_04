from collections.abc import Iterable

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

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
