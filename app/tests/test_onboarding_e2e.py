# =====================================================================================
# 온보딩 백엔드 E2E 테스트 — 실제 MySQL(db_client)로 온보딩 전 구간 계약을 관통 검증한다.
#
# 앱(#58)의 안드로이드 proof는 "앱이 백엔드를 올바로 호출하는가"를 수동으로 본다.
# 이 테스트는 그 짝으로 "백엔드가 온보딩 흐름을 계약대로 처리하는가"를 CI에서 자동으로 못박아,
# 백엔드 변경이 앱-백엔드 계약을 조용히 깨뜨리는 회귀(예: GET /terms 인증 요구 변경)를 막는다.
#
# 커버:
#   - 해피패스: guest → terms(GET) → agreements → session → health-profile
#              → physical-assessment → risk-prediction(completed) → home(latest_prediction)
#              + onboarding_status 전이 pending → terms_agreed → completed
#   - 엣지: GET /terms 미인증 401 · 빈 agreements 422 · 필수 약관 거부 400
#          · 체력검사 상호배제 위반 422 · 세션 skip 경로 → completed
# (게스트 재로그인=새 계정은 test_integration_smoke.py에서 이미 커버.)
# =====================================================================================
from httpx import AsyncClient
from starlette import status

_CARE_STAGES = {"good", "maintain", "action_needed"}
_LEVELS = {"easy", "normal", "hard"}


async def _guest_auth(db_client: AsyncClient) -> dict[str, str]:
    """게스트 로그인 → pending 확인 후 인증 헤더를 돌려준다."""
    login = await db_client.post("/api/v1/auth/guest")
    assert login.status_code == status.HTTP_200_OK
    assert login.json()["user"]["onboarding_status"] == "pending"
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def _agreements_payload(
    db_client: AsyncClient, auth: dict[str, str], *, decline: str | None = None
) -> dict[str, list[dict[str, object]]]:
    """GET /terms 응답을 그대로 에코해 동의 바디를 만든다.
    marketing(선택)은 false, 나머지(필수)는 true. decline에 준 terms_type은 강제로 false.
    """
    terms = (await db_client.get("/api/v1/terms", headers=auth)).json()["terms"]
    assert terms, "약관 카탈로그가 비어있으면 온보딩 불가"
    return {
        "agreements": [
            {
                "terms_type": t["terms_type"],
                "version": t["version"],
                "agreed": t["terms_type"] != "marketing" and t["terms_type"] != decline,
            }
            for t in terms
        ]
    }


async def test_onboarding_happy_path_guest_to_completed(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    # 2) 약관 목록 — 인증 필요(계약 ⓐ). 미인증 401은 아래 별도 테스트.
    terms = await db_client.get("/api/v1/terms", headers=auth)
    assert terms.status_code == status.HTTP_200_OK
    assert terms.json()["terms"]

    # 3) 약관 동의(필수 true, marketing false) → terms_agreed
    agree = await db_client.post(
        "/api/v1/users/me/agreements", json=await _agreements_payload(db_client, auth), headers=auth
    )
    assert agree.status_code == status.HTTP_200_OK
    assert agree.json()["onboarding_status"] == "terms_agreed"

    # 4) 건강체크 세션 → started
    session = await db_client.post(
        "/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth
    )
    assert session.status_code == status.HTTP_201_CREATED
    sid = session.json()["session_id"]
    assert session.json()["status"] == "started"

    # 5) 건강 프로필 → profile_id, bmi
    profile = await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": sid,
            "birth_date": "1958-03-21",
            "sex": "male",
            "height_cm": 168,
            "weight_kg": 63.5,
            "waist_cm": 84,
            "walking_practice": True,
            "strength_exercise": False,
            "kidney_status": "none",
            "protein_restriction_status": "none",
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert profile.status_code == status.HTTP_201_CREATED
    profile_id = profile.json()["profile_id"]
    assert profile_id > 0
    assert profile.json()["bmi"] > 0

    # 6) 기초체력검사 → activity_profile.current_level 산정
    assessment = await db_client.post(
        "/api/v1/physical-assessments",
        json={
            "session_id": sid,
            "assessment_type": "initial",
            "chair_stand_5_time_sec": 12.4,
            "chair_stand_skipped": False,
            "pain_reported": False,
            "dizziness_reported": False,
        },
        headers=auth,
    )
    assert assessment.status_code == status.HTTP_201_CREATED
    assert assessment.json()["activity_profile"]["current_level"] in _LEVELS

    # 7) 위험도 예측 → onboarding_status completed (pending→terms_agreed→completed 최종 전이)
    risk = await db_client.post(
        "/api/v1/risk-predictions", json={"profile_id": profile_id}, headers=auth
    )
    assert risk.status_code == status.HTTP_201_CREATED
    risk_body = risk.json()
    assert risk_body["onboarding_status"] == "completed"
    assert risk_body["care_stage"] in _CARE_STAGES
    assert risk_body["display_message"]
    # 결과 화면 고지: 참고용·비진단 disclaimer 노출(#56/#57 정렬 계약)
    assert risk_body["disclaimer"]
    # 연속 risk_score는 공개 계약이다. 내부 구현 필드는 계속 비노출인지 관통 수준에서 검사한다.
    for internal_key in ("internal_risk_score", "internal_risk_level", "model_version"):
        assert internal_key not in risk_body

    # 8) 홈 → latest_prediction 노출(온보딩 성공 판정)
    home = await db_client.get("/api/v1/home", headers=auth)
    assert home.status_code == status.HTTP_200_OK
    latest = home.json()["latest_prediction"]
    assert latest is not None
    assert latest["care_stage"] in _CARE_STAGES


async def test_get_terms_requires_auth_returns_401(db_client: AsyncClient) -> None:
    # 미인증 호출 → 401 (계약 ⓐ: GET /terms 인증 필요). 이게 깨지면 앱 배선 전제가 무너진다.
    resp = await db_client.get("/api/v1/terms")
    assert resp.status_code == status.HTTP_401_UNAUTHORIZED


async def test_agreements_empty_list_returns_422(db_client: AsyncClient) -> None:
    # 빈 배열 → Pydantic Field(min_length=1) 스키마 위반 422
    auth = await _guest_auth(db_client)
    resp = await db_client.post("/api/v1/users/me/agreements", json={"agreements": []}, headers=auth)
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


async def test_agreements_declining_required_returns_400(db_client: AsyncClient) -> None:
    # 필수 약관(service)을 agreed=false로 → 비즈니스 규칙 위반 400
    auth = await _guest_auth(db_client)
    payload = await _agreements_payload(db_client, auth, decline="service")
    resp = await db_client.post("/api/v1/users/me/agreements", json=payload, headers=auth)
    assert resp.status_code == status.HTTP_400_BAD_REQUEST


async def test_physical_assessment_mutual_exclusion_returns_422(db_client: AsyncClient) -> None:
    # chair_stand_skipped=false 인데 chair_stand_5_time_sec 누락 → 상호배제 위반 422
    auth = await _guest_auth(db_client)
    resp = await db_client.post(
        "/api/v1/physical-assessments",
        json={
            "assessment_type": "initial",
            "chair_stand_skipped": False,
        },
        headers=auth,
    )
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


async def test_health_check_skip_completes_onboarding(db_client: AsyncClient) -> None:
    # 체력검사 스킵 대체 경로: 세션 생성 후 skip → onboarding completed + 기본 난이도
    auth = await _guest_auth(db_client)
    await db_client.post(
        "/api/v1/users/me/agreements", json=await _agreements_payload(db_client, auth), headers=auth
    )
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    ).json()["session_id"]

    skip = await db_client.post(f"/api/v1/health-check/sessions/{sid}/skip", headers=auth)
    assert skip.status_code == status.HTTP_200_OK
    body = skip.json()
    assert body["onboarding_status"] == "completed"
    assert body["activity_profile"]["current_level"] in _LEVELS


async def test_legacy_walk_6m_payload_ignored_band_from_5sts(db_client: AsyncClient) -> None:
    # 구버전 앱이 walk_6m_*를 계속 보내도 422가 아니라 200/201로 무시(deprecated no-op)되고,
    #   밴드는 5STS 단독으로 산출된다(6m 유무와 무관, 리뷰 #118-3).
    auth = await _guest_auth(db_client)
    await db_client.post(
        "/api/v1/users/me/agreements", json=await _agreements_payload(db_client, auth), headers=auth
    )
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    ).json()["session_id"]
    # 나이 68세(1958년생) → 65-69 규준 11.4초. 5STS 12.4 > 11.4 → easy.
    await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": sid, "birth_date": "1958-03-21", "sex": "male",
            "height_cm": 168, "weight_kg": 63.5, "walking_practice": True, "strength_exercise": False,
            "activity_input_source": "self_report", "input_method": "form", "has_estimated_value": False,
        },
        headers=auth,
    )
    resp = await db_client.post(
        "/api/v1/physical-assessments",
        json={
            "assessment_type": "initial",
            "chair_stand_5_time_sec": 12.4,
            "chair_stand_skipped": False,
            # 구버전 앱 잔재(deprecated no-op): 무시돼야 하고 밴드에 영향 없음.
            "walk_6m_time_sec": 6.1,
            "walk_6m_distance_m": 6.0,
            "walk_6m_skipped": False,
        },
        headers=auth,
    )
    assert resp.status_code == status.HTTP_201_CREATED
    body = resp.json()
    assert "walk_6m_speed_mps" not in resp.text  # 응답 계약에도 6m 없음
    assert body["activity_profile"]["current_level"] == "easy"  # 5STS 12.4 > 11.4 → easy
