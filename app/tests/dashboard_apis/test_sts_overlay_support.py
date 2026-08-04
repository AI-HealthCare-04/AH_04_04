# =====================================================================================
# §3.4 근력 기능 안전망 카드 지원 백엔드 E2E:
#   - POST /events/sts-overlay-shown : 카드 노출 이벤트 수집
#   - 노출 분해 집계 계약: scripts.sts_overlay_report (EXPOSURE_BREAKDOWN)
# (muscle-score-context 조회와 sts-score-viewed 이벤트는 난이도 폐기 리팩터링에서 제거됨)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.analytics import StsOverlayEvent
from scripts.sts_overlay_report import EXPOSURE_BREAKDOWN

API = "/api/v1"


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    login = await db_client.post(f"{API}/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def test_sts_overlay_shown_is_recorded(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    resp = await db_client.post(
        f"{API}/events/sts-overlay-shown",
        json={"tier": "strong", "sts_sec": 13.5, "bmi": 26.0, "score_band": "maintain"},
        headers=auth,
    )
    assert resp.status_code == status.HTTP_201_CREATED

    async with db_sessionmaker() as s:
        row = await s.scalar(select(StsOverlayEvent).where(StsOverlayEvent.user_id == user_id))
        assert row is not None
        assert row.tier == "strong"
        assert row.score_band == "maintain"
        assert row.sts_sec is not None and float(row.sts_sec) == 13.5


async def test_sts_overlay_shown_rejects_invalid_tier(db_client: AsyncClient) -> None:
    auth, _ = await _guest(db_client)
    resp = await db_client.post(f"{API}/events/sts-overlay-shown", json={"tier": "extreme"}, headers=auth)
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


async def _seed_event(
    s: AsyncSession,
    table: str,
    user_id: int,
    *,
    days_ago: int,
    tier: str | None = None,
    sts_sec: float | None = None,
) -> None:
    """집계 테스트용 시드. created_at 을 DB 시계 기준 과거로 밀어 넣는다(주 경계 계산과 동일 시계)."""
    columns = ["user_id", "created_at"]
    values = [":u", "DATE_SUB(NOW(), INTERVAL :d DAY)"]
    params: dict[str, object] = {"u": user_id, "d": days_ago}
    if tier:
        columns.insert(1, "tier")
        values.insert(1, ":t")
        params["t"] = tier
    if sts_sec is not None:
        columns.append("sts_sec")
        values.append(":sts")
        params["sts"] = sts_sec
    await s.execute(
        text(f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({', '.join(values)})"),  # noqa: S608
        params,
    )


async def test_exposure_breakdown_averages_are_user_weighted(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """#373 리뷰 P2: 컷 재조정 근거인 평균은 사용자 단위 균등 가중이어야 한다 —
    재시도가 많은 사용자의 원시 행이 평균을 끌고 가면(행 가중) 컷 판단이 편향된다."""
    auth_a, user_a = await _guest(db_client)
    auth_b, user_b = await _guest(db_client)

    async with db_sessionmaker() as s:
        # A: 같은 노출이 재시도로 3행(sts 20.0), B: 1행(sts 12.0).
        #   행 가중 평균 = (20*3 + 12) / 4 = 18.0 (오염) / 사용자 가중 평균 = (20 + 12) / 2 = 16.0 (정답)
        for _ in range(3):
            await _seed_event(s, "sts_overlay_events", user_a, days_ago=0, tier="basic", sts_sec=20.0)
        await _seed_event(s, "sts_overlay_events", user_b, days_ago=0, tier="basic", sts_sec=12.0)
        await s.commit()

        rows = [dict(r) for r in (await s.execute(EXPOSURE_BREAKDOWN, {"weeks": 12})).mappings()]

    assert len(rows) == 1
    row = rows[0]
    assert row["tier"] == "basic"
    assert row["events"] == 4, "행 수는 재시도 포함 원본 그대로"
    assert row["users"] == 2
    assert float(row["avg_sts_sec"]) == 16.0, "재시도 3행이 평균을 18.0 으로 끌고 가면 안 된다"


# DTO-DB 입력 경계(지영 리뷰): 저장 스키마를 넘는 값은 commit 단계 500 이 아니라 422 로 거부돼야 한다.
async def test_sts_overlay_shown_rejects_out_of_range_inputs(db_client: AsyncClient) -> None:
    auth, _ = await _guest(db_client)
    # sts_sec 상한(Numeric(5,2) → 999.99) 초과
    over_sts = await db_client.post(
        f"{API}/events/sts-overlay-shown", json={"tier": "strong", "sts_sec": 1000}, headers=auth
    )
    assert over_sts.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
    # bmi 상한(Numeric(4,1) → 999.9) 초과
    over_bmi = await db_client.post(
        f"{API}/events/sts-overlay-shown", json={"tier": "strong", "bmi": 1000}, headers=auth
    )
    assert over_bmi.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
    # 음수(ge=0) 거부
    neg = await db_client.post(f"{API}/events/sts-overlay-shown", json={"tier": "basic", "sts_sec": -1}, headers=auth)
    assert neg.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
    # score_band 확정값(good/maintain/caution) 외 문자열 거부 → DB String(20) 초과 불가
    bad_band = await db_client.post(
        f"{API}/events/sts-overlay-shown",
        json={"tier": "strong", "score_band": "x" * 30},
        headers=auth,
    )
    assert bad_band.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
