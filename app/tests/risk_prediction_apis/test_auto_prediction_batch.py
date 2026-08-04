# =====================================================================================
# 자정 자동 예측 — 실 MySQL 통합 테스트.
#
# 여기서만 잡히는 것 두 가지라 통합으로 둔다.
#   1) **카운터 분리** — 자동 예측이 그날의 수동 재평가 권리를 소진하지 않는가. 가짜 repo 로는
#      `get_today_reassessment` 의 where 절(is_auto=False)이 실제로 걸리는지 확인할 수 없다.
#   2) **배치 멱등** — 두 번 돌려도 하루 한 점인가. 대상 조회와 저장이 같은 DB 를 봐야 의미가 있다.
# =====================================================================================
import asyncio

from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.predictions import RiskPrediction
from app.models.users import User
from app.services.auto_prediction import run_daily_auto_predictions

REASSESS = "/api/v1/risk-predictions/reassess"
BODY = {"activity_window_days": 7}


async def _onboarded_user(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> tuple[dict[str, str], int]:
    """게스트 로그인 → 약관 → 세션 → 건강 프로필(만 65세 이상)까지 API 로 밟고 (헤더, user_id) 반환."""
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


async def _prediction_counts(sm: async_sessionmaker[AsyncSession], user_id: int) -> tuple[int, int]:
    """(자동 예측 수, 수동 재평가 수)."""
    async with sm() as s:
        auto = await s.scalar(
            select(func.count())
            .select_from(RiskPrediction)
            .where(RiskPrediction.user_id == user_id, RiskPrediction.is_auto.is_(True))
        )
        manual = await s.scalar(
            select(func.count())
            .select_from(RiskPrediction)
            .where(
                RiskPrediction.user_id == user_id,
                RiskPrediction.is_reassessment.is_(True),
                RiskPrediction.is_auto.is_(False),
            )
        )
    return int(auto or 0), int(manual or 0)


async def test_auto_prediction_does_not_consume_manual_reassess(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """자정 배치가 돈 뒤에도 사용자는 그날 재평가를 한 번 쓸 수 있다(카운터 분리).

    카운터를 나누지 않으면 `get_today_reassessment` 가 자동 예측을 집어 recalculated=False 로
    돌려준다 — 내 정보를 고치고 눌러도 수정이 다음 날까지 반영되지 않는다.
    """
    auth, user_id = await _onboarded_user(db_client, db_sessionmaker)

    summary = await run_daily_auto_predictions(db_sessionmaker)
    assert summary.created == 1
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 0)

    # 자동 예측이 있어도 수동 재평가는 새로 계산된다.
    manual = await db_client.post(REASSESS, json=BODY, headers=auth)
    assert manual.status_code == status.HTTP_201_CREATED
    assert manual.json()["recalculated"] is True
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 1)

    # 수동은 여전히 하루 1회다 — 두 번째는 같은 예측을 돌려준다.
    again = await db_client.post(REASSESS, json=BODY, headers=auth)
    assert again.json()["recalculated"] is False
    assert again.json()["prediction_id"] == manual.json()["prediction_id"]
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 1)


async def test_manual_reassess_does_not_block_auto_prediction(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """반대 방향도 성립한다 — 사용자가 먼저 눌렀어도 그날 자동 예측은 따로 남는다."""
    auth, user_id = await _onboarded_user(db_client, db_sessionmaker)

    manual = await db_client.post(REASSESS, json=BODY, headers=auth)
    assert manual.json()["recalculated"] is True

    summary = await run_daily_auto_predictions(db_sessionmaker)
    assert summary.created == 1
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 1)


async def test_batch_is_idempotent_within_a_day(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """두 번 돌려도 하루 한 점 — 기동 보정이 자정 실행과 겹쳐도 안전해야 한다.

    배치를 '시각'이 아니라 '오늘 자동 예측이 없는 사용자'로 정의한 근거가 이 성질이다.
    """
    _, user_id = await _onboarded_user(db_client, db_sessionmaker)

    first = await run_daily_auto_predictions(db_sessionmaker)
    assert (first.targeted, first.created) == (1, 1)

    second = await run_daily_auto_predictions(db_sessionmaker)
    assert second.targeted == 0  # 대상 조회에서 이미 빠진다
    assert second.created == 0
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 0)


async def test_concurrent_batches_create_one_prediction(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """두 배치가 **동시에** 돌아도 하루 한 점이다(리뷰 P1).

    단일 워커여도 겹치는 경로가 있다 — 기동 보정과 00:05 cron 은 서로 다른 작업이라
    `max_instances` 가 막지 못하고, 00:05 직전에 기동하면 둘이 같이 돈다.

    보조 트랜잭션이 users 행을 먼저 잠가 두 배치를 같은 FOR UPDATE 대기열에 세운 뒤 풀어서,
    확실히 경합 상태에서 출발하게 만든다(gather 만으로는 한쪽이 먼저 끝나 경합 없이 지나간다).
    잠금이 없으면 둘 다 '오늘 자동 예측 없음'을 읽어 예측이 2행 생긴다.
    """
    _, user_id = await _onboarded_user(db_client, db_sessionmaker)

    async with db_sessionmaker() as blocker:
        await blocker.execute(select(User.user_id).where(User.user_id == user_id).with_for_update())
        first_task = asyncio.create_task(run_daily_auto_predictions(db_sessionmaker))
        second_task = asyncio.create_task(run_daily_auto_predictions(db_sessionmaker))
        await asyncio.sleep(0.5)  # 두 배치가 FOR UPDATE 대기열에 진입할 시간
        await blocker.rollback()  # 잠금 해제 → 두 트랜잭션이 순서대로 경쟁
        first, second = await asyncio.gather(first_task, second_task)

    # 둘 다 대상 1명을 봤지만(경합 전 조회) 실제로 만든 쪽은 하나뿐이다.
    assert first.targeted == second.targeted == 1
    assert first.created + second.created == 1, (first, second)
    # 저장도 한 벌만 — 이 검증이 하루 1건 보장의 실제 근거다.
    assert await _prediction_counts(db_sessionmaker, user_id) == (1, 0)


async def test_batch_skips_users_without_completed_onboarding(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """온보딩 미완료 사용자는 대상이 아니다 — 프로필이 없어 계산할 것도 없다."""
    login = await db_client.post("/api/v1/auth/guest")
    assert login.status_code == status.HTTP_200_OK

    summary = await run_daily_auto_predictions(db_sessionmaker)
    assert summary.targeted == 0
