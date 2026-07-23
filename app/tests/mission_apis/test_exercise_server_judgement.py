# =====================================================================================
# 운동 성공 판정 서버 이관 (실 MySQL).
#
# 걷기와 같은 규칙으로 맞춘다: 당일 누적 시간 >= 목표(분). 앱이 보낸 success는 신뢰하지 않는다.
# 원래 목표가 '3회(count)'였는데 회당 4분이라 3회면 10분이 넘는다는 전제였다. 그 전제는
# 끝까지 했을 때만 참이라, 시작하자마자 끄기를 3번 해도 성공이 됐다.
#
# 여기서 고정하는 계약:
#   - 앱이 success=true 를 보내도 누적이 목표 미만이면 실패 (우회 차단)
#   - 앱이 success=false 를 보내도 누적이 목표 이상이면 성공 (판정 주체는 서버)
#   - 여러 번 나눠 해도 합산된다 — 같은 운동 반복도 인정
#   - 포인트·카운트는 목표를 '처음 넘긴' 로그에만 1회 (걷기와 동일)
#   - duration_min <= 0 은 요청 단계에서 거부 (음수로 누적을 되돌리는 우회 차단)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionTemplate

API = "/api/v1"


async def _guest(db_client: AsyncClient) -> dict[str, str]:
    body = (await db_client.post(f"{API}/auth/guest")).json()
    return {"Authorization": f"Bearer {body['access_token']}"}


async def _seed_exercise_template(
    sm: async_sessionmaker[AsyncSession], *, target_min: int = 10, reward_points: int = 10
) -> int:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=MissionType.EXERCISE,
            title="영상 따라 운동하기(테스트)",
            level=ActivityLevel.EASY,
            display_order=1,
            default_target_value=target_min,
            target_unit=TargetUnit.MINUTES,
            reward_points=reward_points,
        )
        s.add(template)
        await s.flush()
        template_id = template.mission_template_id
        await s.commit()
    return template_id


async def _perform(
    db_client: AsyncClient, auth: dict[str, str], template_id: int, *, minutes: float, success: bool = True
) -> dict:
    """운동 1회 수행: 시작(in_progress) → 완료(PATCH)."""
    created = await db_client.post(
        f"{API}/mission-logs",
        json={
            "mission_template_id": template_id,
            "mission_type": "exercise",
            "status": "in_progress",
        },
        headers=auth,
    )
    log_id = created.json()["mission_log_id"]
    done = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={
            "status": "completed",
            "success": success,
            "exercise_detail": {"duration_min": minutes},
        },
        headers=auth,
    )
    return done.json()


# -------------------------------------------------------------------------------------
# 1. 중간에 끈 회차를 여러 번 해도 누적이 모자라면 성공이 아니다
# -------------------------------------------------------------------------------------
async def test_short_sessions_do_not_succeed_even_if_app_claims_success(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    # 30초씩 3번 = 1.5분. 회차로 세면 3회지만 누적은 목표(10분)에 한참 못 미친다.
    for _ in range(3):
        body = await _perform(db_client, auth, template_id, minutes=0.5, success=True)
        assert body["success"] is False
        assert body["counted_for_daily"] is False

    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 0


# -------------------------------------------------------------------------------------
# 2. 여러 번 나눠 해도 누적으로 목표를 넘으면 성공 (같은 운동 반복 인정)
# -------------------------------------------------------------------------------------
async def test_repeated_sessions_accumulate_to_success(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    first = await _perform(db_client, auth, template_id, minutes=4.2)
    assert first["success"] is False  # 4.2분 — 아직 목표 미달

    second = await _perform(db_client, auth, template_id, minutes=4.2)
    assert second["success"] is False  # 8.4분

    third = await _perform(db_client, auth, template_id, minutes=4.2)
    assert third["success"] is True  # 12.6분 — 목표 도달
    assert third["counted_for_daily"] is True
    assert third["daily_total_min"] == 12.6  # 앱이 진행률을 그릴 수 있도록 합산값을 돌려준다

    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 10


# -------------------------------------------------------------------------------------
# 3. 목표를 넘긴 뒤 더 해도 포인트는 하루 1회만 (걷기와 동일)
# -------------------------------------------------------------------------------------
async def test_points_awarded_once_even_if_user_keeps_going(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    await _perform(db_client, auth, template_id, minutes=12)  # 첫 회에 목표 초과
    extra = await _perform(db_client, auth, template_id, minutes=5)

    assert extra["success"] is True  # 목표는 이미 넘었으므로 성공 상태 유지
    assert extra["counted_for_daily"] is False  # 그러나 다시 세지 않는다

    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 10


# -------------------------------------------------------------------------------------
# 4. 판정 주체는 서버 — 앱이 success=false 를 보내도 누적이 차면 성공
# -------------------------------------------------------------------------------------
async def test_server_overrides_client_success_flag(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    body = await _perform(db_client, auth, template_id, minutes=15, success=False)

    assert body["success"] is True
    assert body["counted_for_daily"] is True


# -------------------------------------------------------------------------------------
# 5. 음수·0 시간은 요청 단계에서 거부 (누적을 되돌리는 우회 차단)
# -------------------------------------------------------------------------------------
async def test_non_positive_duration_is_rejected(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    created = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "exercise", "status": "in_progress"},
        headers=auth,
    )
    log_id = created.json()["mission_log_id"]

    for bad in (-5, 0):
        resp = await db_client.patch(
            f"{API}/mission-logs/{log_id}",
            json={"status": "completed", "success": True, "exercise_detail": {"duration_min": bad}},
            headers=auth,
        )
        assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY


# -------------------------------------------------------------------------------------
# 6. duration_min 없이 완료 시도 → 400, 완료 상태로 굳지 않아 재시도가 가능해야 한다
# -------------------------------------------------------------------------------------
async def test_completion_without_duration_is_rejected_and_retryable(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """duration_min 없이 완료하면 누적 0·실패로 굳고, 멱등 유니크 때문에 재완료도 409 로 막힌다.

    그래서 완료 상태로 전환하기 '전에' 400 으로 막아야 한다(리뷰 #159). 막은 뒤에는 로그가
    아직 in_progress 라, 시간을 담아 다시 완료 요청하면 정상 처리돼야 한다.
    """
    auth = await _guest(db_client)
    template_id = await _seed_exercise_template(db_sessionmaker)

    created = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "exercise", "status": "in_progress"},
        headers=auth,
    )
    log_id = created.json()["mission_log_id"]

    # duration_min 을 뺀 완료 → 400 (필드 자체는 optional 이라 스키마는 통과, 서비스가 막는다)
    missing = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={"status": "completed", "success": True, "exercise_detail": {"reps": 10}},
        headers=auth,
    )
    assert missing.status_code == status.HTTP_400_BAD_REQUEST

    # 로그가 완료로 굳지 않았으므로, 시간을 담아 다시 완료하면 성공한다.
    retry = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={"status": "completed", "success": True, "exercise_detail": {"duration_min": 12}},
        headers=auth,
    )
    assert retry.status_code == status.HTTP_200_OK
    assert retry.json()["success"] is True
