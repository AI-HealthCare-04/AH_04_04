# =====================================================================================
# Mission 도메인 Service — 비즈니스 규칙 담당.
#   - GET /missions            : 사용자 레벨로 필터한 미션 목록
#   - POST /mission-logs       : 운동/걷기 시작(in_progress) or 식사/게임 즉시완료(completed)
#   - PATCH /mission-logs/{id} : 운동 완료 / 걷기 종료
#   - GET /mission-logs        : 일자별 조회
#
# 규칙 요약:
#   - 식사(meal)는 1일 1회만 카운트 → 초과 시 daily_limit_reached=True (저장은 하되 미카운트)
#   - 걷기(walking)는 같은 날 자동 서버 합산 → daily_total_min 반환
#   - 성공 시에만 포인트 지급 (mission_scoring.compute_earned_points)
# =====================================================================================
from datetime import date
from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.mission import (
    MissionLogCreateRequest,
    MissionLogCreateResponse,
    MissionLogUpdateRequest,
    MissionLogUpdateResponse,
    MissionResponse,
)
from app.models.enums import (
    ActivityLevel,
    ActivitySource,
    ActivityType,
    DailyResult,
    MissionStatus,
    MissionType,
    SyncStatus,
)
from app.models.health import HealthProfile
from app.models.missions import GameLog, MealLog, MissionLog, MissionTemplate, PhysicalActivityLog
from app.models.users import User
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.mission_repository import MissionRepository
from app.services.mission_scoring import compute_daily_result, compute_earned_points


class MissionService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = MissionRepository(session)
        self.health_repo = HealthProfileRepository(session)

    # ---------------- GET /missions ----------------

    async def get_missions(
        self,
        user: User,
        mission_type: MissionType | None,
        level: ActivityLevel | None,
    ) -> list[MissionResponse]:
        # 레벨 우선순위: 쿼리로 명시한 값 > 사용자의 현재 레벨 > (없으면 전체)
        effective_level = level or await self.repo.get_user_current_level(user.user_id)
        # 안전 필터: 신장/단백질 제한 사용자에게는 고단백(requires_kidney_check) 미션을 숨긴다.
        latest_profile = await self.health_repo.get_latest_profile(user.user_id)
        exclude_kidney_check = self._should_hide_kidney_missions(latest_profile)
        templates = await self.repo.get_active_templates(
            level=effective_level,
            mission_type=mission_type,
            exclude_kidney_check=exclude_kidney_check,
        )
        return [MissionResponse.model_validate(t) for t in templates]

    @staticmethod
    def _should_hide_kidney_missions(profile: HealthProfile | None) -> bool:
        """고단백 미션 숨김 여부. 서버가 이미 계산·저장한 protein_challenge_allowed를
        단일 진실원천으로 사용한다(kidney/protein 규칙 재구현 금지).
        프로필이 없으면(건강체크 전) 숨기지 않는다."""
        if profile is None:
            return False
        return not profile.protein_challenge_allowed

    # ---------------- POST /mission-logs ----------------

    # 종류별 허용 status: 식사/게임은 즉시완료, 운동/걷기는 시작(in_progress)→PATCH 완료
    _IMMEDIATE_TYPES = frozenset({MissionType.MEAL, MissionType.GAME})
    _START_TYPES = frozenset({MissionType.EXERCISE, MissionType.WALKING})

    async def create_mission_log(self, user: User, data: MissionLogCreateRequest) -> MissionLogCreateResponse:
        template = await self.repo.get_template(data.mission_template_id)
        if template is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="존재하지 않는 미션입니다.")

        # 클라이언트가 보낸 mission_type을 신뢰하지 않고 template과 일치하는지 검증한다.
        if data.mission_type != template.mission_type:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="미션 종류가 템플릿과 일치하지 않습니다.",
            )

        # 안전 차단(단일 원천): 목록 숨김(GET /missions)만으로는 캐시된 목록·직접 호출로 우회되므로,
        # 실제 수행을 만드는 여기서도 동일하게 막는다. GET과 같은 protein_challenge_allowed를 쓰며
        # 프로필 없음은 허용(GET 계약과 동일), False인 경우에만 거부한다(신장/단백질 제한 = 카테고리
        # 금지라 재시도로 해소되지 않으므로 403).
        if template.requires_kidney_check:
            latest_profile = await self.health_repo.get_latest_profile(user.user_id)
            if self._should_hide_kidney_missions(latest_profile):
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="신장/단백질 제한으로 수행할 수 없는 미션입니다.",
                )

        # 종류별 허용 status 조합 검증
        if template.mission_type in self._IMMEDIATE_TYPES and data.status != MissionStatus.COMPLETED:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="식사/게임 미션은 즉시 완료로만 생성할 수 있습니다.",
            )
        if template.mission_type in self._START_TYPES and data.status != MissionStatus.IN_PROGRESS:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="운동/걷기 미션은 시작(in_progress)으로만 생성할 수 있습니다.",
            )

        if data.status == MissionStatus.IN_PROGRESS:
            return await self._start_mission(user, data, template)
        return await self._complete_immediately(user, data, template)

    async def _start_mission(
        self, user: User, data: MissionLogCreateRequest, template: MissionTemplate
    ) -> MissionLogCreateResponse:
        # 운동/걷기 시작: 안전 고지가 필요한 미션인데 확인 안 됐으면 막는다.
        if template.requires_safety_notice and not data.safety_notice_confirmed:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="안전 고지 확인이 필요합니다.")

        log = MissionLog(
            user_id=user.user_id,
            mission_template_id=data.mission_template_id,
            mission_type=template.mission_type,
            status=MissionStatus.IN_PROGRESS,
            success=False,
            safety_notice_confirmed=bool(data.safety_notice_confirmed),
            safety_notice_confirmed_at=data.safety_notice_confirmed_at,
            counted_for_daily=False,
            earned_points=0,
            created_on_device_at=data.created_on_device_at,
        )
        await self.repo.create_mission_log(log)
        daily_result = await self._refresh_daily_summary(user.user_id)
        await self.session.commit()
        return MissionLogCreateResponse(
            mission_log_id=log.mission_log_id,
            status=log.status.value,
            success=False,
            counted_for_daily=False,
            daily_limit_reached=False,
            earned_points=0,
            daily_result=daily_result.value,
        )

    async def _complete_immediately(
        self, user: User, data: MissionLogCreateRequest, template: MissionTemplate
    ) -> MissionLogCreateResponse:
        # 식사/게임 즉시완료
        success = bool(data.success)
        daily_limit_reached = False
        counted_for_daily = success

        # 식사는 1일 1회만 카운트
        if template.mission_type == MissionType.MEAL:
            already = await self.repo.count_meal_missions_today(user.user_id)
            if already >= 1:
                daily_limit_reached = True
                counted_for_daily = False

        earned_points = compute_earned_points(counted_for_daily, template.reward_points)

        log = MissionLog(
            user_id=user.user_id,
            mission_template_id=data.mission_template_id,
            mission_type=template.mission_type,
            status=MissionStatus.COMPLETED,
            performed_at=data.created_on_device_at,
            actual_value=data.actual_value,
            target_value=data.target_value,
            target_unit=data.target_unit,
            success=success,
            input_method=data.input_method,
            counted_for_daily=counted_for_daily,
            earned_points=earned_points,
            created_on_device_at=data.created_on_device_at,
        )
        await self.repo.create_mission_log(log)

        # 상세 저장 (mission_log 1:1)
        if data.meal_detail is not None:
            await self.repo.add_meal_log(
                MealLog(
                    mission_log_id=log.mission_log_id,
                    meal_date=today_kst(),
                    protein_foods=data.meal_detail.protein_foods,
                    protein_meal_count=data.meal_detail.protein_meal_count,
                    raw_text=data.meal_detail.raw_text,
                    counted_for_daily=counted_for_daily,
                )
            )
        if data.game_detail is not None:
            gd = data.game_detail
            await self.repo.add_game_log(
                GameLog(
                    mission_log_id=log.mission_log_id,
                    game_type=gd.game_type,
                    score=gd.score,
                    duration_sec=gd.duration_sec,
                    success_count=gd.success_count,
                    mistake_count=gd.mistake_count,
                    completed=gd.completed,
                )
            )

        daily_result = await self._refresh_daily_summary(user.user_id)
        await self.session.commit()
        return MissionLogCreateResponse(
            mission_log_id=log.mission_log_id,
            status=log.status.value,
            success=success,
            counted_for_daily=counted_for_daily,
            daily_limit_reached=daily_limit_reached,
            earned_points=earned_points,
            daily_result=daily_result.value,
        )

    # ---------------- PATCH /mission-logs/{id} ----------------

    @staticmethod
    def _validate_completion_detail(mission_type: MissionType, data: MissionLogUpdateRequest) -> None:
        """완료 시 mission_type ↔ detail 조합 검증.
        걷기는 walking_detail만, 운동은 exercise_detail만 허용하고, 해당 detail은 필수로 요구한다.
        (detail 없이 success=true로 완료해 집계/포인트가 반영되는 것을 막는다.)
        """
        if mission_type == MissionType.WALKING:
            if data.walking_detail is None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="걷기 완료에는 walking_detail이 필요합니다."
                )
            if data.exercise_detail is not None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="걷기 미션에는 exercise_detail을 보낼 수 없습니다."
                )
        elif mission_type == MissionType.EXERCISE:
            if data.exercise_detail is None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="운동 완료에는 exercise_detail이 필요합니다."
                )
            if data.walking_detail is not None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="운동 미션에는 walking_detail을 보낼 수 없습니다."
                )

    async def update_mission_log(
        self, user: User, mission_log_id: int, data: MissionLogUpdateRequest
    ) -> MissionLogUpdateResponse:
        log = await self.repo.get_mission_log(mission_log_id, user.user_id)
        if log is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="미션 로그를 찾을 수 없습니다.")

        # 이미 완료된 로그의 재완료를 막는다. (상세 로그 unique 제약으로 500 나는 것 방지)
        if log.status != MissionStatus.IN_PROGRESS:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="이미 완료된 미션입니다.",
            )

        self._validate_completion_detail(log.mission_type, data)

        template = await self.repo.get_template(log.mission_template_id)
        reward_points = template.reward_points if template else 0

        success = bool(data.success)
        counted_for_daily = success

        # mission_log 완료 처리
        log.status = MissionStatus.COMPLETED
        log.actual_value = Decimal(str(data.actual_value)) if data.actual_value is not None else None
        log.target_value = Decimal(str(data.target_value)) if data.target_value is not None else None
        log.target_unit = data.target_unit
        log.success = success
        log.input_method = data.input_method
        log.manual_override = data.manual_override
        log.perceived_difficulty = data.perceived_difficulty
        log.pain_reported = data.pain_reported
        log.dizziness_reported = data.dizziness_reported
        log.counted_for_daily = counted_for_daily
        log.earned_points = compute_earned_points(counted_for_daily, reward_points)

        daily_total_min: float | None = None
        daily_total_steps: int | None = None

        # 걷기 종료: physical_activity_logs 저장 + 같은 날 합산
        if data.walking_detail is not None:
            wd = data.walking_detail
            await self.repo.add_physical_activity_log(
                PhysicalActivityLog(
                    mission_log_id=log.mission_log_id,
                    activity_date=today_kst(),
                    activity_type=ActivityType.WALKING,
                    duration_min=wd.duration_min,
                    distance_km=wd.distance_km,
                    steps=wd.steps,
                    source=ActivitySource.SENSOR,
                    sync_status=SyncStatus.SYNCED,
                )
            )
            daily_total_min = await self.repo.sum_walking_minutes_today(user.user_id)
            daily_total_steps = await self.repo.sum_walking_steps_today(user.user_id)

        # 운동 완료: physical_activity_logs 저장
        elif data.exercise_detail is not None:
            ed = data.exercise_detail
            await self.repo.add_physical_activity_log(
                PhysicalActivityLog(
                    mission_log_id=log.mission_log_id,
                    activity_date=today_kst(),
                    activity_type=ed.activity_type or ActivityType.SEATED_EXERCISE,
                    intensity=ed.intensity,
                    duration_min=ed.duration_min,
                    reps=ed.reps,
                    sets=ed.sets,
                    met_value=ed.met_value,
                    source=ActivitySource.MANUAL,
                    sync_status=SyncStatus.SYNCED,
                )
            )

        daily_result = await self._refresh_daily_summary(user.user_id)
        await self.session.commit()
        return MissionLogUpdateResponse(
            mission_log_id=log.mission_log_id,
            status=log.status.value,
            daily_total_min=daily_total_min,
            daily_total_steps=daily_total_steps,
            success=success,
            counted_for_daily=counted_for_daily,
            daily_result=daily_result.value,
            sync_status=SyncStatus.SYNCED.value,
        )

    # ---------------- GET /mission-logs ----------------

    async def list_mission_logs(self, user: User, on_date: date | None) -> list[MissionLog]:
        return await self.repo.list_mission_logs(user.user_id, on_date)

    # ---------------- 공통: 오늘자 요약 재계산 ----------------

    async def _refresh_daily_summary(self, user_id: int) -> DailyResult:
        breakdown = await self.repo.counted_breakdown_today(user_id)
        meal = breakdown.get(MissionType.MEAL, 0)
        exercise = breakdown.get(MissionType.EXERCISE, 0)
        walking = breakdown.get(MissionType.WALKING, 0)
        game = breakdown.get(MissionType.GAME, 0)
        counted = meal + exercise + walking + game
        points = await self.repo.sum_earned_points_today(user_id)
        daily_result = compute_daily_result(counted)
        await self.repo.upsert_daily_summary(
            user_id=user_id,
            counted_mission_count=counted,
            meal_counted=meal > 0,
            exercise_count=exercise,
            walking_count=walking,
            game_count=game,
            earned_points=points,
            daily_result=daily_result,
        )
        return daily_result
