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
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.protein_categories import PROTEIN_CATEGORY_IDS, PROTEIN_DAILY_GOAL_COUNT
from app.core.utils.clock import today_kst
from app.dtos.mission import (
    MealTodayLog,
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
        # 레벨 우선순위: 쿼리로 명시한 값 > 사용자의 현재 레벨 > EASY(기본).
        #   기본 EASY는 홈(dashboard get_home)과 동일 규칙 — 목록·홈 요약의 걷기 노출을 일치시키고,
        #   프로필 없는 사용자(온보딩 직후 등)에게 걷기 3종이 전부 보이던 문제를 막는다.
        #   (레벨 필터는 걷기에만 적용 — repo.get_active_templates 참고)
        effective_level = level or await self.repo.get_user_current_level(user.user_id) or ActivityLevel.EASY
        # 안전 필터: 신장/단백질 제한 사용자에게는 고단백(requires_kidney_check) 미션을 숨긴다.
        latest_profile = await self.health_repo.get_latest_profile(user.user_id)
        exclude_kidney_check = self._should_hide_kidney_missions(latest_profile)
        templates = await self.repo.get_active_templates(
            level=effective_level,
            mission_type=mission_type,
            exclude_kidney_check=exclude_kidney_check,
        )
        responses = [MissionResponse.model_validate(t) for t in templates]
        meal_template_ids = [
            template.mission_template_id for template in templates if template.mission_type == MissionType.MEAL
        ]
        today_meals = await self.repo.get_today_meal_logs(user.user_id, meal_template_ids)
        # 단백질(식사) 미션엔 오늘 저장된 기록을 붙여, 앱이 재진입 시 카드 선택 상태를 복원하게 한다(지시서 §4.1).
        for resp, template in zip(responses, templates, strict=True):
            meal = today_meals.get(template.mission_template_id)
            if meal is not None:
                resp.today_log = MealTodayLog(eaten=meal.protein_foods, logged_at=meal.created_at)
        return responses

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

        # 오프라인 재전송 방어(#91, #105) — 여기서 먼저 걸러 아래 검증·삽입을 아예 타지 않는다.
        #   응답을 못 받은 앱이 outbox 로 같은 수행을 다시 보내면, 새 행을 만들지 않고 기존 것을 돌려준다.
        #   기록이 이미 남아 있으므로 그 사이 프로필이 바뀌어(신장 제한 등) 지금은 거부될 수행이라도
        #   재전송은 성공해야 한다 — 그래서 안전 필터보다 앞에 둔다.
        already = await self._find_resent_log(user, data)
        if already is not None:
            return await self._existing_log_response(already)

        await self._ensure_can_create(user, data, template)

        try:
            if data.status == MissionStatus.IN_PROGRESS:
                return await self._start_mission(user, data, template)
            return await self._complete_immediately(user, data, template)
        except IntegrityError:
            # 위 조회와 삽입 사이에 같은 수행이 들어온 경우(재전송 2건 동시 도착).
            #   유니크 제약이 두 번째를 막아주므로, 롤백하고 먼저 들어간 것을 돌려준다.
            #   제약이 없으면 이 경합에서 중복 행이 생긴다 — 조회만으로는 못 막는다.
            await self.session.rollback()
            raced = await self._find_resent_log(user, data)
            if raced is None:
                raise  # 재전송 경합이 아닌 진짜 무결성 오류 — 삼키지 않는다.
            return await self._existing_log_response(raced)

    async def _find_resent_log(self, user: User, data: MissionLogCreateRequest) -> MissionLog | None:
        """같은 수행이 이미 기록돼 있으면 그 로그를 준다(재전송 판별).

        created_on_device_at 을 안 보내는 클라이언트는 판별할 근거가 없으므로 항상 None.

        ## 왜 status·payload 를 비교하지 않는가 (안 A 확정, 리뷰 #158)
          자연 키(user_id, mission_template_id, created_on_device_at) 가 "어느 수행인가"를 유일하게
          결정한다. 같은 키면 같은 수행으로 보고 status 불문 기존 로그를 그대로 돌려준다.

          status 를 비교하면 '지연 재전송'이 깨진다:
            POST(in_progress) 성공 → 응답 유실 → 사용자가 측정을 마쳐 PATCH 로 completed →
            그 뒤 outbox 의 POST(in_progress) 가 뒤늦게 재전송된다.
          이때 DB 는 completed, 재전송 payload 는 in_progress 라, status 를 비교하면 정상 재전송이
          409 로 거부된다. 그래서 비교하지 않고 기존(completed) 로그를 200 으로 돌려준다 —
          앱은 이미 완료된 수행으로 처리하면 된다.

          "서로 다른 수행이 우연히 같은 키" 는 (같은 유저 + 같은 템플릿 + 같은 마이크로초,
          DATETIME(6)) 라 사실상 불가능하고, 앱이 로컬 수행 생성 시각을 키로 삼아 재전송 때
          동일 값을 되쏘는 것을 전제로 한다(PR 본문). 이 잔여 리스크를 받는 것이 안 A 의 트레이드오프다.
        """
        if data.created_on_device_at is None:
            return None
        return await self.repo.find_mission_log_by_device_time(
            user_id=user.user_id,
            mission_template_id=data.mission_template_id,
            created_on_device_at=data.created_on_device_at,
        )

    async def _ensure_can_create(self, user: User, data: MissionLogCreateRequest, template: MissionTemplate) -> None:
        """새 수행을 만들 수 있는 요청인지 검증. 재전송은 이 검증을 타지 않는다."""
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

        # 상세 payload 는 해당 미션 유형에만 허용한다(지영 리뷰 #230 비차단).
        #   어긋난 조합(예: game 요청에 meal_detail)을 조용히 무시하면 클라이언트 버그가 늦게
        #   발견된다 — 계약 위반은 400 으로 즉시 드러낸다.
        if data.meal_detail is not None and template.mission_type != MissionType.MEAL:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="meal_detail 은 식사(meal) 미션에만 보낼 수 있습니다.",
            )
        if data.game_detail is not None and template.mission_type != MissionType.GAME:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="game_detail 은 게임(game) 미션에만 보낼 수 있습니다.",
            )

        # 단백질(식사) 미션: eaten(protein_foods)은 정의된 7개 카테고리 id 만 허용(정의 밖이면 400, 지시서 §4.2).
        #   빈 배열은 허용("오늘 안 먹었어요"도 기록) — 완료 판정은 PROTEIN_DAILY_GOAL_COUNT종 이상에서 성립한다.
        if template.mission_type == MissionType.MEAL and data.meal_detail is not None:
            invalid = set(data.meal_detail.protein_foods) - PROTEIN_CATEGORY_IDS
            if invalid:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"허용되지 않은 단백질 카테고리입니다: {sorted(invalid)}",
                )

    async def _existing_log_response(self, log: MissionLog) -> MissionLogCreateResponse:
        """이미 기록된 수행을 그대로 돌려준다(재전송). 새로 만들지도, 값을 바꾸지도 않는다."""
        # 요약은 로그로부터 다시 계산하는 멱등 연산이라, 재전송으로 값이 흔들리지 않는다.
        #   앱이 최신 daily_result 를 받도록 여기서도 한 번 갱신한다.
        daily_result = await self._refresh_daily_summary(log.user_id)
        await self.session.commit()
        # '식사 1일 1회 초과' 복원 — **#224 이전 옛 데이터 전용**(지영 리뷰 #230 비차단 정리).
        #   현행 upsert 계약에서는 식사 로그가 항상 success == counted_for_daily 라 이 조건이 성립하지
        #   않지만, 1일 1회 제한 시절에 만들어진 행(success=True·counted=False)의 재전송에는 당시와
        #   같은 안내("이미 제출하셨어요")가 나가야 하므로 복원 로직을 유지한다.
        daily_limit_reached = log.mission_type == MissionType.MEAL and log.success and not log.counted_for_daily
        return MissionLogCreateResponse(
            mission_log_id=log.mission_log_id,
            status=log.status.value,
            success=log.success,
            counted_for_daily=log.counted_for_daily,
            daily_limit_reached=daily_limit_reached,
            earned_points=log.earned_points,
            daily_result=daily_result.value,
            deduplicated=True,
        )

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
        # 식사(단백질) 미션은 당일 1건 upsert(재저장=최신 선택으로 갱신, 서버가 완료판정). 게임은 아래 기존 경로.
        if template.mission_type == MissionType.MEAL:
            return await self._upsert_meal(user, data, template)
        # 게임 즉시완료 — 식사(MEAL)는 위에서 _upsert_meal 로 전부 라우팅되므로 여기 오지 않는다.
        #   (과거 식사 1일 1회 카운트·meal_detail 저장 블록은 #224 upsert 도입으로 죽은 코드가 됐고,
        #    특히 meal_detail 저장은 카테고리 검증(_upsert_meal 전용)을 우회해 비검증 값을 저장할 수
        #    있는 구멍이라 제거했다 — 지영 리뷰 #227 비차단 후속.)
        success = bool(data.success)
        daily_limit_reached = False
        counted_for_daily = success

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

        # 상세 저장 (mission_log 1:1) — meal 상세는 _upsert_meal 에서만 저장한다(검증·정규화 일원화).
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

    async def _upsert_meal(
        self, user: User, data: MissionLogCreateRequest, template: MissionTemplate
    ) -> MissionLogCreateResponse:
        """단백질(식사) 미션 저장 — 당일 1건을 upsert 한다.
        - 완료판정은 서버가 한다: 서로 다른 카테고리 PROTEIN_DAILY_GOAL_COUNT종 이상이면 success. 클라 success 는 무시.
        - 같은 날 재저장은 기존 기록을 최신 선택으로 갱신(append 아님, 지시서 §3-3).
        - 빈 배열도 저장(기록 가치) — 완료(카운트)는 PROTEIN_DAILY_GOAL_COUNT종 이상에서만.
        오프라인 '같은 payload 재전송'은 상위 _find_resent_log 가 이미 걸러 여기 오지 않는다.
        """
        detail = data.meal_detail
        # 상세가 없으면 빈 기록으로 취급(빈 배열 허용) — 완료(카운트)는 PROTEIN_DAILY_GOAL_COUNT종 이상에서만.
        foods = list(dict.fromkeys(detail.protein_foods)) if detail is not None else []  # 중복 제거·순서 유지
        raw_text = detail.raw_text if detail is not None else None
        count = len(foods)
        success = count >= PROTEIN_DAILY_GOAL_COUNT
        counted_for_daily = success
        earned_points = compute_earned_points(counted_for_daily, template.reward_points)

        # 당일 upsert 경합 방지(지영 리뷰 #224): 조회~생성/갱신을 사용자 단위로 직렬화한다.
        #   users 행을 먼저 FOR UPDATE 로 잠근 뒤 당일 기록을 읽어야, 서로 다른 payload(중복 클릭·
        #   네트워크 재시도로 dedup 자연키가 갈리는 경우)가 동시에 도착해도 두 요청이 각각 None 을
        #   읽고 새 로그를 만드는 일이 없다 — get_today_meal_log 조합엔 DB 유일 제약이 없어(걷기/운동
        #   완료와 달리) 조회만으로는 못 막는다. 두 번째 요청은 lock 획득 후 첫 로그를 보고 갱신 경로로 간다.
        await self.repo.lock_user_for_completion(user.user_id)
        existing_meal = await self.repo.get_today_meal_log(user.user_id, data.mission_template_id)
        if existing_meal is not None:
            # 재저장: 당일 기록(로그·상세)을 최신 선택으로 갱신한다.
            #   created_on_device_at(=dedup 자연키)은 '첫 저장' 값으로 유지한다 — 갱신하면 첫 저장의 오프라인
            #   재전송이 dedup 을 놓쳐 오래된 payload 로 되돌릴 수 있다. 재전송은 기존(최신) 기록을 그대로 받는다.
            existing_meal.protein_foods = foods
            # 자유텍스트도 최신 요청 값으로 갱신(지영 리뷰 #224): 카테고리만 갱신하고 raw_text 를
            #   남겨두면 '최신 카테고리 + 옛 설명' 혼합 상태가 된다.
            existing_meal.raw_text = raw_text
            mission_log = await self.repo.get_mission_log(existing_meal.mission_log_id, user.user_id)
            if mission_log is not None:
                mission_log.success = success
                mission_log.counted_for_daily = counted_for_daily
                mission_log.earned_points = earned_points
                mission_log.performed_at = data.created_on_device_at
            log_id = existing_meal.mission_log_id
            log_status = mission_log.status.value if mission_log is not None else MissionStatus.COMPLETED.value
        else:
            log = MissionLog(
                user_id=user.user_id,
                mission_template_id=data.mission_template_id,
                mission_type=MissionType.MEAL,
                status=MissionStatus.COMPLETED,
                performed_at=data.created_on_device_at,
                success=success,
                input_method=data.input_method,
                counted_for_daily=counted_for_daily,
                earned_points=earned_points,
                created_on_device_at=data.created_on_device_at,
            )
            await self.repo.create_mission_log(log)
            await self.repo.add_meal_log(
                MealLog(
                    mission_log_id=log.mission_log_id,
                    meal_date=today_kst(),
                    protein_foods=foods,
                    raw_text=raw_text,
                )
            )
            log_id = log.mission_log_id
            log_status = log.status.value

        daily_result = await self._refresh_daily_summary(user.user_id)
        await self.session.commit()
        return MissionLogCreateResponse(
            mission_log_id=log_id,
            status=log_status,
            success=success,
            counted_for_daily=counted_for_daily,
            daily_limit_reached=False,  # upsert 라 '1일 1회 초과' 개념 없음(재저장은 갱신).
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
            # 성공 판정이 '당일 누적 시간'이라 duration_min 이 없으면 이 수행은 0분으로 집계된다.
            #   그대로 COMPLETED 로 넘기면 누적 0·실패로 굳고, 멱등 유니크 때문에 재완료도 409 로 막힌다.
            #   그래서 완료 상태로 전환하기 '전에' 여기서 막는다(리뷰 #159).
            if data.exercise_detail.duration_min is None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="운동 완료에는 수행 시간(duration_min)이 필요합니다.",
                )
            if data.walking_detail is not None:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="운동 미션에는 walking_detail을 보낼 수 없습니다."
                )

    async def update_mission_log(
        self, user: User, mission_log_id: int, data: MissionLogUpdateRequest
    ) -> MissionLogUpdateResponse:
        # 완료를 사용자 단위로 직렬화한다(트랜잭션 첫 읽기 = users 행 FOR UPDATE).
        #   동시 걷기 완료가 같은 스냅샷에서 둘 다 '최초 목표 달성'으로 판정해 이중 적립되던 race를 막고,
        #   locking read 이후 첫 consistent read가 잠금 획득(선행 커밋) 뒤에 스냅샷을 잡아 재집계도 정확해진다.
        await self.repo.lock_user_for_completion(user.user_id)
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

        # 완료 처리 — success/counted/points는 걷기 서버판정을 위해 상세 반영 후 확정한다.
        log.status = MissionStatus.COMPLETED
        log.actual_value = Decimal(str(data.actual_value)) if data.actual_value is not None else None
        log.target_value = Decimal(str(data.target_value)) if data.target_value is not None else None
        log.target_unit = data.target_unit
        log.input_method = data.input_method
        log.manual_override = data.manual_override
        log.perceived_difficulty = data.perceived_difficulty
        log.pain_reported = data.pain_reported
        log.dizziness_reported = data.dizziness_reported

        daily_total_min: float | None = None
        daily_total_steps: int | None = None

        # 걷기 종료: physical_activity_logs 저장 + 같은 날 합산
        if data.walking_detail is not None:
            wd = data.walking_detail
            # 물리 활동 snapshot은 클라이언트의 중복 입력 대신 서버 판정값을 저장한다.
            log.actual_value = Decimal(str(wd.duration_min))
            log.target_value = Decimal(str(template.default_target_value)) if template else None
            log.target_unit = template.target_unit if template else None
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
            # 분·걸음을 한 SELECT로 함께 읽어 시점이 어긋난 쌍을 방지(동일 statement snapshot).
            daily_total_min, daily_total_steps = await self.repo.sum_walking_totals_today(user.user_id)
            # 걷기 성공은 서버가 판정: 당일 누적 시간(daily_total_min) >= 난이도 목표(분).
            #   클라이언트 success는 신뢰하지 않는다(1분만 걷고 success=true 우회 차단).
            #   포인트·카운트는 하루 1회만: 목표를 '이번 세션에서 처음 넘긴' 로그에만 지급하고,
            #   이미 목표를 넘어선 뒤의 추가 걷기는 success=true지만 미카운트·미적립(중복 방지).
            #   동시 요청 race는 update_mission_log 첫머리의 사용자 행 잠금으로 직렬화되어 안전하다.
            target_min = float(template.default_target_value) if template else 0.0
            prior_min = daily_total_min - float(wd.duration_min)
            success = daily_total_min >= target_min
            counted_for_daily = prior_min < target_min <= daily_total_min

        # 운동 완료: physical_activity_logs 저장 + 걷기와 같은 '당일 누적' 서버 판정.
        elif data.exercise_detail is not None:
            ed = data.exercise_detail
            # _validate_completion_detail 에서 운동 완료 시 duration_min 필수를 이미 강제했다.
            duration_min = ed.duration_min if ed.duration_min is not None else 0.0
            log.actual_value = Decimal(str(duration_min))
            log.target_value = Decimal(str(template.default_target_value)) if template else None
            log.target_unit = template.target_unit if template else None
            await self.repo.add_physical_activity_log(
                PhysicalActivityLog(
                    mission_log_id=log.mission_log_id,
                    activity_date=today_kst(),
                    activity_type=ed.activity_type or ActivityType.SEATED_EXERCISE,
                    intensity=ed.intensity,
                    duration_min=duration_min,
                    reps=ed.reps,
                    sets=ed.sets,
                    met_value=ed.met_value,
                    source=ActivitySource.MANUAL,
                    sync_status=SyncStatus.SYNCED,
                )
            )
            # 운동 성공도 서버가 판정한다: 당일 누적 시간(daily_total_min) >= 목표(분).
            #   클라이언트 success는 신뢰하지 않는다 — 걷기와 같은 이유다(30초만 보고 success=true 차단).
            #   같은 운동을 여러 번 해도 되고(반복 허용), 중간에 그만둔 회차는 누적이 덜 차 자연히 걸러진다.
            #   포인트·카운트는 하루 1회: 목표를 '이번 세션에서 처음 넘긴' 로그에만 지급한다(걷기와 동일).
            #   목표 단위는 항상 분(minutes)이다 — 운동 템플릿은 시드·마이그레이션(0010)으로 분으로 통일돼 있고,
            #   count 등 다른 단위로 운동 템플릿을 추가하면 이 판정이 어긋난다(그때 함께 손봐야 한다).
            daily_total_min = await self.repo.sum_exercise_minutes_today(user.user_id)
            target_min = float(template.default_target_value) if template else 0.0
            prior_min = daily_total_min - float(duration_min)
            success = daily_total_min >= target_min
            counted_for_daily = prior_min < target_min <= daily_total_min
        else:
            success = bool(data.success)
            counted_for_daily = success

        log.success = success
        log.counted_for_daily = counted_for_daily
        log.earned_points = compute_earned_points(counted_for_daily, reward_points)

        # daily_summary 집계 쿼리가 이 로그의 counted_for_daily를 보도록 먼저 flush한다.
        #   (걷기 서버판정 때문에 상세 반영 후 확정하므로, add_physical_activity_log의 flush 시점엔
        #    아직 미확정이었다. autoflush=False 환경에서도 정확히 집계되게 명시적으로 flush.)
        await self.session.flush()
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

    async def get_today_walking_totals(self, user: User) -> tuple[float, int]:
        # 홈 '오늘 걷기' 위젯용 당일 누적 실적(분·걸음). 걷기 완료 응답과 같은 원천·같은 단일 SELECT를
        #   재사용해 홈·완료가 동일 스냅샷의 일관된 쌍을 본다(분·걸음 torn-read 방지, KST 오늘). 없으면 (0.0, 0).
        return await self.repo.sum_walking_totals_today(user.user_id)

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
