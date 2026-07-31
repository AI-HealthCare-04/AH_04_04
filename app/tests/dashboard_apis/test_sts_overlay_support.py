# =====================================================================================
# §3.4 근력 기능 안전망 카드 지원 백엔드 E2E:
#   - GET  /dashboard/muscle-score-context : 최신 5STS(초) + BMI (카드 발화 입력)
#   - POST /events/sts-overlay-shown        : 카드 노출 이벤트 수집(발화율 관측)
# =====================================================================================
from decimal import Decimal

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.analytics import StsOverlayEvent
from app.models.enums import AssessmentType
from app.models.health import PhysicalAssessment

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
            "birth_date": "1958-03-21", "sex": "male", "height_cm": 170, "weight_kg": 70,
            "walk_days": 5, "musc_days": 0, "activity_input_source": "self_report",
            "input_method": "form", "has_estimated_value": False,
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
    resp = await db_client.post(
        f"{API}/events/sts-overlay-shown", json={"tier": "extreme"}, headers=auth
    )
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


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
    neg = await db_client.post(
        f"{API}/events/sts-overlay-shown", json={"tier": "basic", "sts_sec": -1}, headers=auth
    )
    assert neg.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
    # score_band 확정값(good/maintain/caution) 외 문자열 거부 → DB String(20) 초과 불가
    bad_band = await db_client.post(
        f"{API}/events/sts-overlay-shown",
        json={"tier": "strong", "score_band": "x" * 30},
        headers=auth,
    )
    assert bad_band.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
