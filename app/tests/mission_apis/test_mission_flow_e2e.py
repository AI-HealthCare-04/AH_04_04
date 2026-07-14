# =====================================================================================
# 2차 목표(홈·미션 수행 완결) E2E 통합 테스트 (실 MySQL).
#
# "홈 → 미션 완료/수행기록 → 포인트·스탬프·남은 미션 수 갱신 → 홈 반영"의 전체 흐름을
# 게스트 로그인부터 실제 API·DB로 한 번에 검증한다. (단위/스텁 테스트가 못 잡는
# 실모델·집계·트랜잭션 반영을 실제로 확인한다.)
#
# 로직 자체는 이미 구현돼 있으므로, 이 파일은 "현재 확정된 동작"을 계약으로 고정한다:
#   - 식사(meal)만 1일 1회 카운트 → 초과 시 daily_limit_reached=True, 미카운트/미적립
#   - 게임·운동·걷기는 일일 한도 없음(반복 카운트)
#   - 성공(success)한 미션만 카운트·포인트 적립
#   - daily_result: 카운트 1개 이상=success, 3개 이상=great_success
#   - 완료된 로그 재완료는 409 (포인트 이중 적립 없음)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.core.utils.clock import today_kst
from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionTemplate

API = "/api/v1"


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    """게스트 로그인 → (Authorization 헤더, user_id)."""
    login = await db_client.post(f"{API}/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def _seed_template(
    sm: async_sessionmaker[AsyncSession],
    *,
    mission_type: MissionType,
    reward_points: int = 10,
    level: ActivityLevel = ActivityLevel.EASY,
    daily_count_limit: int | None = None,
    target_unit: TargetUnit = TargetUnit.COUNT,
    display_order: int = 1,
) -> int:
    """미션 템플릿 1건을 시드하고 mission_template_id를 반환한다.
    게스트는 활동 레벨 프로필이 없어 홈의 available_mission_summary가 EASY 템플릿을 세므로,
    홈 반영을 보는 테스트는 level=EASY(기본값)로 둔다."""
    async with sm() as s:
        template = MissionTemplate(
            mission_type=mission_type,
            title=f"{mission_type.value} 미션",
            level=level,
            display_order=display_order,
            default_target_value=1,
            target_unit=target_unit,
            reward_points=reward_points,
            daily_count_limit=daily_count_limit,
        )
        s.add(template)
        await s.flush()
        template_id = template.mission_template_id
        await s.commit()
    return template_id


def _meal_body(template_id: int, *, success: bool = True) -> dict:
    return {
        "mission_template_id": template_id,
        "mission_type": "meal",
        "status": "completed",
        "success": success,
        "meal_detail": {"protein_foods": ["두부"], "protein_meal_count": 1},
    }


def _game_body(template_id: int, *, success: bool = True) -> dict:
    return {
        "mission_template_id": template_id,
        "mission_type": "game",
        "status": "completed",
        "success": success,
        "game_detail": {"game_type": "card_match", "completed": success},
    }


# -------------------------------------------------------------------------------------
# 1. 식사 완료 → 포인트 적립 → 홈·포인트 API 반영
# -------------------------------------------------------------------------------------
async def test_meal_complete_awards_points_and_reflects_on_home(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=10, daily_count_limit=1
    )

    resp = await db_client.post(f"{API}/mission-logs", json=_meal_body(template_id), headers=auth)
    assert resp.status_code == status.HTTP_201_CREATED
    body = resp.json()
    assert body["counted_for_daily"] is True
    assert body["daily_limit_reached"] is False
    assert body["earned_points"] == 10
    assert body["daily_result"] == "success"

    # 홈: 잔액·오늘 카운트·오늘 결과 반영 + 식사 한도 소진으로 available.meal 0
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["point_balance"]["current_points"] == 10
    assert home["today_summary"]["counted_mission_count"] == 1
    assert home["today_summary"]["daily_result"] == "success"
    assert home["available_mission_summary"]["meal"] == 0

    # 포인트 API: 잔액·적립이력
    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 10
    assert len(points["earn_logs"]) == 1
    assert points["earn_logs"][0]["reason"] == "meal"
    assert points["earn_logs"][0]["earned_points"] == 10


# -------------------------------------------------------------------------------------
# 2. 식사 2회째 → 일일 한도 도달 (미카운트·미적립), 잔액 불변
# -------------------------------------------------------------------------------------
async def test_meal_second_completion_hits_daily_limit(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=10, daily_count_limit=1
    )

    first = (await db_client.post(f"{API}/mission-logs", json=_meal_body(template_id), headers=auth)).json()
    assert first["counted_for_daily"] is True and first["earned_points"] == 10

    second = await db_client.post(f"{API}/mission-logs", json=_meal_body(template_id), headers=auth)
    assert second.status_code == status.HTTP_201_CREATED  # 저장은 되지만
    body = second.json()
    assert body["daily_limit_reached"] is True
    assert body["counted_for_daily"] is False
    assert body["earned_points"] == 0

    # 잔액은 여전히 10 (이중 적립 없음), 카운트도 1 유지
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["point_balance"]["current_points"] == 10
    assert home["today_summary"]["counted_mission_count"] == 1
    assert home["available_mission_summary"]["meal"] == 0


# -------------------------------------------------------------------------------------
# 3. 실패한 미션 → 미카운트·미적립·daily_result none
# -------------------------------------------------------------------------------------
async def test_failed_mission_is_not_counted_and_earns_nothing(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)

    resp = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, success=False), headers=auth)
    body = resp.json()
    assert body["counted_for_daily"] is False
    assert body["earned_points"] == 0
    assert body["daily_result"] == "none"

    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["point_balance"]["current_points"] == 0
    assert home["today_summary"]["counted_mission_count"] == 0
    assert home["today_summary"]["daily_result"] == "none"


# -------------------------------------------------------------------------------------
# 4. 운동: 시작(in_progress) → 완료(PATCH) → 포인트 적립·활동 로그
# -------------------------------------------------------------------------------------
async def test_exercise_start_then_complete_awards_points(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.EXERCISE, reward_points=15, target_unit=TargetUnit.MINUTES
    )

    start = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "exercise", "status": "in_progress"},
        headers=auth,
    )
    assert start.status_code == status.HTTP_201_CREATED
    start_body = start.json()
    assert start_body["status"] == "in_progress"
    assert start_body["counted_for_daily"] is False
    assert start_body["earned_points"] == 0
    log_id = start_body["mission_log_id"]

    done = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={
            "status": "completed",
            "success": True,
            "exercise_detail": {"activity_type": "seated_exercise", "duration_min": 10},
        },
        headers=auth,
    )
    assert done.status_code == status.HTTP_200_OK
    done_body = done.json()
    assert done_body["status"] == "completed"
    assert done_body["counted_for_daily"] is True
    assert done_body["daily_result"] == "success"
    assert done_body["sync_status"] == "synced"
    assert done_body["daily_total_min"] is None  # 걷기 전용 필드 → 운동은 None

    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["point_balance"]["current_points"] == 15
    assert home["today_summary"]["counted_mission_count"] == 1


# -------------------------------------------------------------------------------------
# 5. 걷기: 같은 날 두 번 완료 → daily_total_min 서버 합산
# -------------------------------------------------------------------------------------
async def test_walking_completions_sum_daily_total_min(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.WALKING, reward_points=5, target_unit=TargetUnit.MINUTES
    )

    async def _walk(minutes: float) -> dict:
        start = await db_client.post(
            f"{API}/mission-logs",
            json={"mission_template_id": template_id, "mission_type": "walking", "status": "in_progress"},
            headers=auth,
        )
        log_id = start.json()["mission_log_id"]
        done = await db_client.patch(
            f"{API}/mission-logs/{log_id}",
            json={"status": "completed", "success": True, "walking_detail": {"duration_min": minutes}},
            headers=auth,
        )
        assert done.status_code == status.HTTP_200_OK
        return done.json()

    first = await _walk(10)
    assert first["daily_total_min"] == 10

    second = await _walk(15)
    assert second["daily_total_min"] == 25  # 같은 날 합산

    # 걷기는 한도 없음 → 2건 모두 카운트
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["today_summary"]["counted_mission_count"] == 2
    assert home["point_balance"]["current_points"] == 10


# -------------------------------------------------------------------------------------
# 6. 완료된 로그 재완료 → 409, 포인트 이중 적립 없음
# -------------------------------------------------------------------------------------
async def test_recompleting_finished_log_returns_409_without_double_points(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.EXERCISE, reward_points=15, target_unit=TargetUnit.MINUTES
    )

    start = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "exercise", "status": "in_progress"},
        headers=auth,
    )
    log_id = start.json()["mission_log_id"]
    complete_body = {
        "status": "completed",
        "success": True,
        "exercise_detail": {"activity_type": "seated_exercise", "duration_min": 10},
    }

    first = await db_client.patch(f"{API}/mission-logs/{log_id}", json=complete_body, headers=auth)
    assert first.status_code == status.HTTP_200_OK

    again = await db_client.patch(f"{API}/mission-logs/{log_id}", json=complete_body, headers=auth)
    assert again.status_code == status.HTTP_409_CONFLICT

    # 포인트는 15 그대로
    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 15


# -------------------------------------------------------------------------------------
# 7. daily_result 임계값 + 스탬프 반영 (게임 3회 → great_success)
# -------------------------------------------------------------------------------------
async def test_daily_result_thresholds_and_stamps(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)

    results = []
    for _ in range(3):
        resp = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id), headers=auth)
        results.append(resp.json()["daily_result"])
    # 1개→success, 2개→success, 3개→great_success
    assert results == ["success", "success", "great_success"]

    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["today_summary"]["daily_result"] == "great_success"
    assert home["today_summary"]["counted_mission_count"] == 3
    assert home["point_balance"]["current_points"] == 15

    # 스탬프: 오늘 날짜 항목에 반영
    month = today_kst().strftime("%Y-%m")
    stamps = (await db_client.get(f"{API}/dashboard/stamps", params={"month": month}, headers=auth)).json()
    today_iso = today_kst().isoformat()
    today_entry = next((d for d in stamps["days"] if d["date"] == today_iso), None)
    assert today_entry is not None
    assert today_entry["daily_result"] == "great_success"
    assert today_entry["counted_mission_count"] == 3
    assert today_entry["earned_points"] == 15


# -------------------------------------------------------------------------------------
# 8. 다른 사용자의 미션 로그 완료 시도 → 404 (소유권 격리)
# -------------------------------------------------------------------------------------
async def test_patch_other_users_mission_log_returns_404(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth_a, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.EXERCISE, reward_points=15, target_unit=TargetUnit.MINUTES
    )
    start = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "exercise", "status": "in_progress"},
        headers=auth_a,
    )
    log_id = start.json()["mission_log_id"]

    auth_b, _ = await _guest(db_client)  # 다른 게스트
    resp = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={
            "status": "completed",
            "success": True,
            "exercise_detail": {"activity_type": "seated_exercise", "duration_min": 10},
        },
        headers=auth_b,
    )
    assert resp.status_code == status.HTTP_404_NOT_FOUND


# -------------------------------------------------------------------------------------
# 9. 재진입: 홈 조회 → 식사 완료 → 재조회 시 available.meal 실시간 갱신
# -------------------------------------------------------------------------------------
async def test_available_meal_updates_on_reentry(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=10, daily_count_limit=1
    )

    before = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert before["available_mission_summary"]["meal"] == 1

    await db_client.post(f"{API}/mission-logs", json=_meal_body(template_id), headers=auth)

    after = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert after["available_mission_summary"]["meal"] == 0
