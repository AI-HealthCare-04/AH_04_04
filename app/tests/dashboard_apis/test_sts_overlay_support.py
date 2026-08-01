# =====================================================================================
# §3.4 근력 기능 안전망 카드 지원 백엔드 E2E:
#   - GET  /dashboard/muscle-score-context : 최신 5STS(초) + BMI (카드 발화 입력)
#   - POST /events/sts-overlay-shown        : 카드 노출 이벤트 수집(발화율 분자)
#   - POST /events/sts-score-viewed         : 점수 화면 조회 이벤트 수집(발화율 분모, #373)
#   - 주간 발화율 집계·보존 정리 계약(#373): scripts.sts_overlay_report / scripts.prune_sts_events
# =====================================================================================
from decimal import Decimal

from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.analytics import StsOverlayEvent, StsScoreViewEvent
from app.models.enums import AssessmentType
from app.models.health import PhysicalAssessment
from scripts.prune_sts_events import prune
from scripts.sts_overlay_report import (
    WEEKLY_EXPOSED_USERS,
    WEEKLY_VIEWER_USERS,
    merge_weekly_rate,
)

API = "/api/v1"


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    login = await db_client.post(f"{API}/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def _create_profile(db_client: AsyncClient, auth: dict[str, str]) -> None:
    # 키 170·몸무게 70 → BMI 24.2
    await db_client.post(
        f"{API}/health-profiles",
        json={
            "birth_date": "1958-03-21",
            "sex": "male",
            "height_cm": 170,
            "weight_kg": 70,
            "walk_days": 5,
            "musc_days": 0,
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )


async def _seed_assessment(
    sm: async_sessionmaker[AsyncSession], user_id: int, *, sts_sec: Decimal | None, skipped: bool
) -> None:
    async with sm() as s:
        s.add(
            PhysicalAssessment(
                user_id=user_id,
                assessment_type=AssessmentType.INITIAL,
                chair_stand_5_time_sec=sts_sec,
                chair_stand_skipped=skipped,
            )
        )
        await s.commit()


async def test_muscle_score_context_returns_sts_and_bmi(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    await _create_profile(db_client, auth)
    await _seed_assessment(db_sessionmaker, user_id, sts_sec=Decimal("13.5"), skipped=False)

    resp = await db_client.get(f"{API}/dashboard/muscle-score-context", headers=auth)
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()
    assert body["chair_stand_sec"] == 13.5  # 최신 5STS
    assert body["bmi"] == 24.2  # 최신 프로필 BMI(170/70)


async def test_muscle_score_context_null_when_skipped(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    await _create_profile(db_client, auth)
    # 5STS 스킵(시간 null) → chair_stand_sec 는 null(카드 미표시).
    await _seed_assessment(db_sessionmaker, user_id, sts_sec=None, skipped=True)

    body = (await db_client.get(f"{API}/dashboard/muscle-score-context", headers=auth)).json()
    assert body["chair_stand_sec"] is None
    assert body["bmi"] == 24.2


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


# ── #373 발화율 분모·집계·보존 계약 ─────────────────────────────────────────────


async def test_sts_score_viewed_is_recorded(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    resp = await db_client.post(f"{API}/events/sts-score-viewed", headers=auth)
    assert resp.status_code == status.HTTP_201_CREATED

    async with db_sessionmaker() as s:
        row = await s.scalar(select(StsScoreViewEvent).where(StsScoreViewEvent.user_id == user_id))
        assert row is not None
        assert row.created_at is not None


async def test_sts_score_viewed_requires_auth(db_client: AsyncClient) -> None:
    resp = await db_client.post(f"{API}/events/sts-score-viewed")
    assert resp.status_code == status.HTTP_401_UNAUTHORIZED


async def _seed_event(s: AsyncSession, table: str, user_id: int, *, days_ago: int, tier: str | None = None) -> None:
    """집계·보존 테스트용 시드. created_at 을 DB 시계 기준 과거로 밀어 넣는다(주 경계 계산과 동일 시계)."""
    columns = "(user_id, tier, created_at)" if tier else "(user_id, created_at)"
    values = "(:u, :t, DATE_SUB(NOW(), INTERVAL :d DAY))" if tier else "(:u, DATE_SUB(NOW(), INTERVAL :d DAY))"
    params: dict[str, object] = {"u": user_id, "d": days_ago}
    if tier:
        params["t"] = tier
    await s.execute(text(f"INSERT INTO {table} {columns} VALUES {values}"), params)  # noqa: S608


async def test_weekly_ignition_rate_dedupes_users_and_marks_missing_denominator(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """#373 집계 계약: 사용자·ISO 주 단위 COUNT(DISTINCT) — fire-and-forget 재시도 중복(멱등키 없음)이
    지표를 부풀리지 않고, 분모 0 인 주는 0% 가 아니라 '-'(산출 불가)로 표기된다."""
    auth_a, user_a = await _guest(db_client)
    auth_b, user_b = await _guest(db_client)

    async with db_sessionmaker() as s:
        # 이번 주: A 노출 2건(재시도 중복) + A·B 조회(A 는 2건 중복) → 노출 1명 / 조회 2명 = 50.0%
        await _seed_event(s, "sts_overlay_events", user_a, days_ago=0, tier="basic")
        await _seed_event(s, "sts_overlay_events", user_a, days_ago=0, tier="basic")
        await _seed_event(s, "sts_score_view_events", user_a, days_ago=0)
        await _seed_event(s, "sts_score_view_events", user_a, days_ago=0)
        await _seed_event(s, "sts_score_view_events", user_b, days_ago=0)
        # 지난 주(7일 전 = 항상 이전 ISO 주): B 노출만 있고 조회 이벤트 없음 → 발화율 '-'
        await _seed_event(s, "sts_overlay_events", user_b, days_ago=7, tier="strong")
        await s.commit()

        exposed = [dict(r) for r in (await s.execute(WEEKLY_EXPOSED_USERS, {"weeks": 12})).mappings()]
        viewers = [dict(r) for r in (await s.execute(WEEKLY_VIEWER_USERS, {"weeks": 12})).mappings()]

    rows = merge_weekly_rate(exposed, viewers)
    assert len(rows) == 2
    last_week, this_week = rows[0], rows[1]
    assert last_week["노출 사용자"] == 1
    assert last_week["조회 사용자"] == 0
    assert last_week["발화율"] == "-", "분모 없는 주는 0% 로 오독되면 안 된다"
    assert this_week["노출 사용자"] == 1, "같은 사용자의 재시도 중복은 1명으로 접힌다"
    assert this_week["조회 사용자"] == 2
    assert this_week["발화율"] == "50.0%"


async def test_prune_deletes_only_expired_events(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """#373 보존 계약: 90일 경과분만 삭제. dry-run(기본)은 건수만 세고 지우지 않는다."""
    auth, user_id = await _guest(db_client)
    async with db_sessionmaker() as s:
        for table, tier in (("sts_overlay_events", "basic"), ("sts_score_view_events", None)):
            await _seed_event(s, table, user_id, days_ago=91, tier=tier)  # 보존기간 경과
            await _seed_event(s, table, user_id, days_ago=1, tier=tier)  # 보존 대상
        await s.commit()

        dry = await prune(await s.connection(), apply=False)
        await s.commit()
        assert dry == {"sts_overlay_events": 1, "sts_score_view_events": 1}
        remaining_after_dry = await s.scalar(text("SELECT COUNT(*) FROM sts_overlay_events"))
        assert remaining_after_dry == 2, "dry-run 은 삭제하지 않는다"

        applied = await prune(await s.connection(), apply=True)
        await s.commit()
        assert applied == {"sts_overlay_events": 1, "sts_score_view_events": 1}
        for table in ("sts_overlay_events", "sts_score_view_events"):
            remaining = await s.scalar(text(f"SELECT COUNT(*) FROM {table}"))  # noqa: S608
            assert remaining == 1, f"{table}: 최근 이벤트는 남아야 한다"


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
