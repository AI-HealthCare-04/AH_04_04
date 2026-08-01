# =====================================================================================
# #357 옵션 B: 예측 결과 체감 피드백 PUT /api/v1/risk-predictions/{prediction_id}/feedback.
#   - 멱등 계약: 같은 요청 반복은 같은 결과, 다른 응답은 덮어쓰기 — 예측당 1행 유지(UNIQUE).
#   - 소유권: 남의 예측·없는 예측은 동일하게 404(존재 여부 비노출).
#   - 응답은 주관적 체감 신호로 정의한다 — 저장 필드에 피처·점수·모델 버전 복제가 없어야 한다.
# =====================================================================================
from decimal import Decimal

from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ModelVariant, RiskLevel
from app.models.predictions import PredictionFeedback, RiskPrediction
from app.models.users import User


async def _guest_auth(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post("/api/v1/auth/guest")
    assert login.status_code == status.HTTP_200_OK
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def _onboard_and_create_prediction(
    db_client: AsyncClient,
    auth: dict[str, str],
    db_sessionmaker: async_sessionmaker[AsyncSession],
) -> int:
    """온보딩(약관→세션→건강 프로필)을 API 로 밟고, 예측 행은 직접 삽입해 prediction_id 를 돌려준다.

    POST /risk-predictions 는 ML 예측기 실행까지 태우므로, 피드백 계약 검증에는
    프로필 FK 만 갖춘 예측 행을 직접 넣는 편이 빠르고 결정적이다.
    """
    terms = await db_client.get("/api/v1/terms", headers=auth)
    agreements = [
        {"terms_type": t["terms_type"], "version": t["version"], "agreed": True}
        for t in terms.json()["terms"]
    ]
    agree_resp = await db_client.post(
        "/api/v1/users/me/agreements", json={"agreements": agreements}, headers=auth
    )
    assert agree_resp.status_code == status.HTTP_200_OK
    session_resp = await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    assert session_resp.status_code == status.HTTP_201_CREATED
    profile_resp = await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": session_resp.json()["session_id"],
            "birth_date": "1958-03-01",
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

    async with db_sessionmaker() as session:
        user_id = await session.scalar(select(User.user_id).order_by(User.user_id.desc()).limit(1))
        assert user_id is not None
        prediction = RiskPrediction(
            user_id=user_id,
            profile_id=profile_resp.json()["profile_id"],
            model_version="test-model-1",
            model_variant=ModelVariant.MINIMAL,
            internal_risk_score=Decimal("0.123"),
            internal_risk_level=RiskLevel.LOW,
            input_snapshot={},
        )
        session.add(prediction)
        await session.commit()
        return prediction.prediction_id


async def test_put_feedback_creates_row(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest_auth(db_client)
    prediction_id = await _onboard_and_create_prediction(db_client, auth, db_sessionmaker)

    resp = await db_client.put(
        f"/api/v1/risk-predictions/{prediction_id}/feedback",
        json={"response": "different", "reason": "too_high"},
        headers=auth,
    )
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()
    assert body["prediction_id"] == prediction_id
    assert body["response"] == "different"
    assert body["reason"] == "too_high"

    async with db_sessionmaker() as session:
        row = await session.scalar(
            select(PredictionFeedback).where(PredictionFeedback.prediction_id == prediction_id)
        )
        assert row is not None
        assert row.is_test is False  # 실사용 응답 기본값 — 시연 마킹 전에는 집계 대상


async def test_put_feedback_is_idempotent_and_overwrites(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest_auth(db_client)
    prediction_id = await _onboard_and_create_prediction(db_client, auth, db_sessionmaker)
    url = f"/api/v1/risk-predictions/{prediction_id}/feedback"

    first = await db_client.put(url, json={"response": "similar"}, headers=auth)
    retried = await db_client.put(url, json={"response": "similar"}, headers=auth)
    assert first.status_code == retried.status_code == status.HTTP_200_OK
    assert first.json() == retried.json()  # 멱등: 재시도해도 같은 결과

    changed = await db_client.put(url, json={"response": "different", "reason": "too_low"}, headers=auth)
    assert changed.status_code == status.HTTP_200_OK
    assert changed.json()["response"] == "different"
    assert changed.json()["reason"] == "too_low"

    async with db_sessionmaker() as session:
        count = await session.scalar(
            select(func.count())
            .select_from(PredictionFeedback)
            .where(PredictionFeedback.prediction_id == prediction_id)
        )
        assert count == 1  # 덮어쓰기 — 예측당 1행(UNIQUE)
        row = await session.scalar(
            select(PredictionFeedback).where(PredictionFeedback.prediction_id == prediction_id)
        )
        assert row is not None and row.response.value == "different"


async def test_put_feedback_on_other_users_prediction_returns_404(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    owner_auth = await _guest_auth(db_client)
    prediction_id = await _onboard_and_create_prediction(db_client, owner_auth, db_sessionmaker)

    other_auth = await _guest_auth(db_client)
    resp = await db_client.put(
        f"/api/v1/risk-predictions/{prediction_id}/feedback",
        json={"response": "similar"},
        headers=other_auth,
    )
    assert resp.status_code == status.HTTP_404_NOT_FOUND


async def test_put_feedback_unknown_prediction_returns_404(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)
    resp = await db_client.put(
        "/api/v1/risk-predictions/999999999/feedback", json={"response": "similar"}, headers=auth
    )
    assert resp.status_code == status.HTTP_404_NOT_FOUND


async def test_put_feedback_requires_auth(db_client: AsyncClient) -> None:
    resp = await db_client.put("/api/v1/risk-predictions/1/feedback", json={"response": "similar"})
    assert resp.status_code == status.HTTP_401_UNAUTHORIZED


async def test_put_feedback_rejects_unknown_response_value(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)
    resp = await db_client.put(
        "/api/v1/risk-predictions/1/feedback", json={"response": "great"}, headers=auth
    )
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


def test_feedback_stores_no_model_snapshot_duplicates() -> None:
    # 지영 리뷰(#357): 피처·점수·모델 버전은 risk_predictions 에만 존재해야 한다(복제 금지).
    columns = {c.name for c in PredictionFeedback.__table__.columns}
    assert columns == {"feedback_id", "prediction_id", "response", "reason", "is_test", "created_at"}
