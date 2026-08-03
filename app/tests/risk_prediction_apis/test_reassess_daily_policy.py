# =====================================================================================
# #388 재평가 하루 1회 정책 — 실 MySQL 통합 테스트.
#
# 단위 테스트(test_risk_prediction_service.py)는 가짜 repo 로 분기만 고정하므로, 정책의 실제
# 보장인 **동시 요청 직렬화**는 여기서 진짜 트랜잭션 두 개로 검증한다(리뷰 P1).
#   - 순차 재호출: 두 번째는 recalculated=False + 같은 prediction_id, 행이 늘지 않는다.
#   - 동시 재호출: users 행 FOR UPDATE 로 직렬화돼 예측·재평가 프로필이 각각 1행만 생긴다.
# =====================================================================================
import asyncio

from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import InputMethod
from app.models.health import HealthProfile
from app.models.predictions import RiskPrediction
from app.models.users import User

REASSESS = "/api/v1/risk-predictions/reassess"
BODY = {"activity_window_days": 7}


async def _onboarded_user(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> tuple[dict[str, str], int]:
    """게스트 로그인 → 약관 → 세션 → 건강 프로필(만 65세 이상) 까지 API 로 밟고 (헤더, user_id) 반환."""
    login = await db_client.post("/api/v1/auth/guest")
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}

    terms = await db_client.get("/api/v1/terms", headers=auth)
    await db_client.post(
        "/api/v1/users/me/agreements",
        json={
            "agreements": [
                {"terms_type": t["terms_type"], "version": t["version"], "agreed": True} for t in terms.json()["terms"]
            ]
        },
        headers=auth,
    )
    session_resp = await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    profile_resp = await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": session_resp.json()["session_id"],
            "birth_date": "1958-03-01",  # 예측 대상(만 65세 이상)
            "sex": "male",
            "height_cm": 170,
            "weight_kg": 65,
            "walk_days": 3,
            "musc_days": 1,
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert profile_resp.status_code == status.HTTP_201_CREATED

    async with db_sessionmaker() as s:
        user_id = await s.scalar(select(User.user_id).order_by(User.user_id.desc()).limit(1))
    assert user_id is not None
    return auth, int(user_id)


async def _counts(sm: async_sessionmaker[AsyncSession], user_id: int) -> tuple[int, int]:
    """(예측 행 수, 재평가 프로필 행 수) — 재평가 프로필은 input_method=SERVICE_LOG 로 구분된다."""
    async with sm() as s:
        predictions = await s.scalar(
            select(func.count()).select_from(RiskPrediction).where(RiskPrediction.user_id == user_id)
        )
        profiles = await s.scalar(
            select(func.count())
            .select_from(HealthProfile)
            .where(
                HealthProfile.user_id == user_id,
                HealthProfile.input_method == InputMethod.SERVICE_LOG,
            )
        )
    return int(predictions or 0), int(profiles or 0)


async def test_second_reassess_same_day_returns_existing(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _onboarded_user(db_client, db_sessionmaker)

    first = await db_client.post(REASSESS, json=BODY, headers=auth)
    assert first.status_code == status.HTTP_201_CREATED
    assert first.json()["recalculated"] is True
    assert await _counts(db_sessionmaker, user_id) == (1, 1)

    second = await db_client.post(REASSESS, json=BODY, headers=auth)
    assert second.status_code == status.HTTP_201_CREATED
    body = second.json()
    assert body["recalculated"] is False  # 오늘은 이미 계산했다
    assert body["prediction_id"] == first.json()["prediction_id"]  # 같은 예측을 돌려준다
    assert body["next_available_at"] is not None
    assert await _counts(db_sessionmaker, user_id) == (1, 1)  # 예측·프로필 모두 안 늘어난다


async def test_concurrent_reassess_creates_single_prediction(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """리뷰 P1: 연타·재시도로 두 요청이 동시에 와도 하루 1회가 유지돼야 한다.

    보조 트랜잭션이 users 행을 먼저 잠가 두 요청을 같은 FOR UPDATE 대기열에 세운 뒤 풀어서,
    둘 다 확실히 경합 상태에서 출발하게 만든다(gather 만으로는 한쪽이 먼저 끝나 경합 없이 지나갈 수 있다).
    잠금이 없으면 둘 다 '오늘 재평가 없음'을 읽어 예측·프로필이 2행씩 생긴다.
    """
    auth, user_id = await _onboarded_user(db_client, db_sessionmaker)

    async with db_sessionmaker() as blocker:
        await blocker.execute(select(User.user_id).where(User.user_id == user_id).with_for_update())
        first_task = asyncio.create_task(db_client.post(REASSESS, json=BODY, headers=auth))
        second_task = asyncio.create_task(db_client.post(REASSESS, json=BODY, headers=auth))
        await asyncio.sleep(0.5)  # 두 요청이 FOR UPDATE 대기열에 진입할 시간
        await blocker.rollback()  # 잠금 해제 → 두 트랜잭션이 순서대로 경쟁
        first, second = await asyncio.gather(first_task, second_task)

    assert first.status_code == status.HTTP_201_CREATED
    assert second.status_code == status.HTTP_201_CREATED
    bodies = [first.json(), second.json()]
    # 정확히 한 요청만 새로 계산하고, 나머지는 그 결과를 그대로 받는다.
    assert sum(b["recalculated"] for b in bodies) == 1, bodies
    assert bodies[0]["prediction_id"] == bodies[1]["prediction_id"]
    # 저장도 한 벌만 — 이 검증이 정책의 실제 보장이다.
    assert await _counts(db_sessionmaker, user_id) == (1, 1)
