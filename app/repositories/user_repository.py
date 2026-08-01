import uuid
from datetime import datetime
from typing import cast

from sqlalchemy import ColumnElement, Table, delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.models.base import Base
from app.models.users import AuthProvider, OnboardingStatus, User


def user_reachable_conditions(user_id: int) -> dict[Table, ColumnElement[bool]]:
    """users 에서 FK 그래프로 도달 가능한 테이블별로 '이 사용자의 행' 조건을 만든다(#356 리뷰 P2).

    직접 user_id 를 가진 테이블은 물론, mission_logs 를 경유하는 상세 로그처럼 간접 참조하는
    테이블도 부모 PK 서브쿼리로 걸린다. 메타데이터를 순회하므로 **새 테이블이 추가되어도
    (예: #357 prediction_feedbacks) 코드 수정 없이 파기·검증 대상에 포함된다.**
    테스트(test_withdrawal.py)도 이 함수를 사용해 파기와 검증의 대상 집합을 일치시킨다.
    """
    users_table = cast(Table, User.__table__)
    conditions: dict[Table, ColumnElement[bool] | None] = {users_table: users_table.c.user_id == user_id}

    def condition_for(table: Table) -> ColumnElement[bool] | None:
        if table in conditions:
            return conditions[table]
        conditions[table] = None  # 순환 FK 가드: 계산 중 재방문한 경로는 무시한다
        parts: list[ColumnElement[bool]] = []
        for fk in table.foreign_keys:
            parent = fk.column.table
            if parent is table:
                continue
            parent_condition = condition_for(parent)
            if parent_condition is not None:
                parts.append(fk.parent.in_(select(fk.column).where(parent_condition)))
        conditions[table] = or_(*parts) if parts else None
        return conditions[table]

    for table in Base.metadata.sorted_tables:
        condition_for(table)
    return {
        table: condition
        for table, condition in conditions.items()
        if condition is not None and table is not users_table
    }


class UserRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_user(self, user_id: int) -> User | None:
        # 탈퇴한 사용자는 행이 물리 삭제되어 조회되지 않는다 → 인증 경로에서 자동으로 401 처리된다.
        stmt = select(User).where(User.user_id == user_id, User.deleted_at.is_(None))
        return await self.session.scalar(stmt)

    async def get_user_for_update(self, user_id: int) -> User | None:
        """탈퇴 처리용 조회 — users 행에 배타 잠금(FOR UPDATE)을 건다(#356 리뷰 P1).

        탈퇴 트랜잭션이 진행되는 동안, 같은 user_id 를 FK 로 참조하는 동시 INSERT 는
        부모 행 잠금에 막혀 대기한다. 탈퇴가 커밋되면 부모 행이 사라져 FK 위반으로 거부되고,
        INSERT 가 먼저 커밋되면 그 행까지 이번 파기에서 함께 삭제된다 — 어느 순서든
        '204 이후 데이터 잔존'이 불가능하다. 이미 삭제된 경우 None(동시 탈퇴 경합).
        """
        stmt = select(User).where(User.user_id == user_id).with_for_update()
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

    async def purge_user_data(self, user_id: int) -> None:
        """탈퇴 사용자의 연결 데이터를 전부 삭제한다(#356 — 익명화 ≠ 파기).

        [user_reachable_conditions] 가 FK 그래프에서 찾은 테이블을 **자식 → 부모 순서**
        (sorted_tables 역순)로 지운다(ON DELETE CASCADE 미설정). 서브쿼리가 참조하는
        부모 행은 아직 남아 있는 시점이라 안전하다. users 행 자체는 [delete_account] 가 지운다.
        """
        conditions = user_reachable_conditions(user_id)
        for table in reversed(Base.metadata.sorted_tables):
            condition = conditions.get(table)
            if condition is not None:
                await self.session.execute(delete(table).where(condition))
        await self.session.flush()

    async def delete_account(self, user: User) -> None:
        """users 행 물리 삭제(#356 리뷰 P1 — 익명화 잔존 대신 삭제).

        행이 사라지면 (1) UNIQUE(provider, social_id) 가 비워져 같은 소셜 계정 재로그인은
        **신규 가입**이 되고(옵션 2), (2) 탈퇴 커밋 이후 도착하는 늦은 INSERT 는 FK 위반으로
        거부되어 파기 후 데이터가 되살아날 수 없다. 원본 social_id·닉네임도 남지 않는다
        (개인정보 최소화). 자식 데이터는 [purge_user_data] 로 먼저 지워야 FK 에 걸리지 않는다.
        """
        await self.session.delete(user)
        await self.session.flush()
