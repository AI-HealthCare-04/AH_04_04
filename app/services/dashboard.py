import calendar
import re
from datetime import date, timedelta

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.dashboard import (
    ActivityTrendPoint,
    DashboardSummaryResponse,
    HomeActivityProfile,
    HomeAvailableMissionSummary,
    HomeLatestPrediction,
    HomeResponse,
    HomeTodaySummary,
    HomeTodayWalking,
    HomeUser,
    LifestyleRecords,
    PointBalanceResponse,
    PointEarnLogItem,
    PointsResponse,
    RiskChangePoint,
    StampDay,
    StampsResponse,
)
from app.models.dashboard import DailyActivitySummary
from app.models.enums import ActivityLevel, DailyResult, MissionType
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.dashboard_repository import DashboardRepository
from app.services.activity_metrics import moderate_equivalent_min
from app.services.mission import MissionService
from app.services.risk_prediction import RiskPredictionService


class DashboardService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = DashboardRepository(session)
        self.activity_repo = ActivityProfileRepository(session)
        self.mission_service = MissionService(session)
        self.risk_service = RiskPredictionService(session)

    async def get_home(self, user: User) -> HomeResponse:
        current_points = await self.repo.get_current_points(user.user_id)
        summary = await self.repo.get_today_summary(user.user_id)
        # 사용자의 실제 운동 난이도(user_activity_profiles.current_level)를 읽어온다.
        #   프로필이 아직 없으면(건강체크 스킵/기초체력검사 전) 기본 easy로 본다.
        #   표시(activity_profile)와 미션 수 산정에 같은 레벨을 써서
        #   "표시 레벨과 카운트 레벨이 어긋나는" 불일치를 막는다(정인/지영 리뷰 반영).
        profile = await self.activity_repo.get_by_user_id(user.user_id)
        effective_level = profile.current_level if profile else ActivityLevel.EASY
        available = await self._available_mission_summary(user, effective_level, summary)
        latest_prediction = await self._latest_prediction(user)
        # 오늘 걷기 누적 실적(분·걸음). 걷기 완료 응답과 같은 원천(#65)을 재사용 → 홈·완료 화면 값 일치.
        #   목표(분)는 여기 넣지 않는다(GET /missions가 단일 원천). 걷기 없으면 (0.0, 0).
        total_walking_min, total_walking_steps = await self.mission_service.get_today_walking_totals(user)
        return HomeResponse(
            user=HomeUser(nickname=user.nickname),
            point_balance=PointBalanceResponse(current_points=current_points),
            activity_profile=HomeActivityProfile(current_level=effective_level),
            latest_prediction=latest_prediction,
            today_summary=HomeTodaySummary(
                counted_mission_count=summary.counted_mission_count if summary else 0,
                daily_result=summary.daily_result if summary else DailyResult.NONE,
            ),
            available_mission_summary=available,
            today_walking=HomeTodayWalking(
                daily_total_min=total_walking_min,
                daily_total_steps=total_walking_steps,
            ),
        )

    async def _latest_prediction(self, user: User) -> HomeLatestPrediction | None:
        # 예측 도메인 공개 인터페이스를 소비한다(그 서비스가 care_stage/display_message 매핑의 단일 출처).
        # 단독 조회용 메서드라 예측이 없으면 404를 던지므로, 홈에서는 이를 null로 변환한다(명세상 nullable).
        try:
            prediction = await self.risk_service.get_latest_prediction(user)
        except HTTPException as exc:
            if exc.status_code == status.HTTP_404_NOT_FOUND:
                return None
            raise
        return HomeLatestPrediction(
            care_stage=prediction.care_stage,
            display_message=prediction.display_message,
        )

    _SUMMARY_MAX_DAYS = 90
    # 위험도 변화 추이에 담을 최근 예측 개수(예측은 사용자가 산출할 때만 생기는 희소 이벤트).
    _RISK_CHANGE_LIMIT = 14

    async def get_summary(self, user: User, days: int) -> DashboardSummaryResponse:
        # 최근 days일(오늘 포함) 구간의 활동 추이·생활기록·위험도 변화를 집계한다.
        if days < 1 or days > self._SUMMARY_MAX_DAYS:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"days는 1~{self._SUMMARY_MAX_DAYS} 사이여야 합니다.",
            )
        end = today_kst()
        start = end - timedelta(days=days - 1)

        # 활동 추이: 신체활동 로그를 일자별로 중강도 상당 분으로 환산·합산.
        #   구간 전체 날짜를 0으로 채워, 활동 없는 날도 추이에 0으로 나타난다.
        per_day: dict[date, float] = {start + timedelta(days=offset): 0.0 for offset in range(days)}
        logs = await self.repo.get_activity_logs_between(user.user_id, start, end)
        for log in logs:
            if log.activity_date in per_day:
                per_day[log.activity_date] += moderate_equivalent_min(
                    log.activity_type, log.intensity, log.duration_min, log.met_value
                )
        activity_trend = [
            ActivityTrendPoint(date=day, moderate_equivalent_min=round(value, 1))
            for day, value in sorted(per_day.items())
        ]
        total = round(sum(per_day.values()), 1)

        # 생활 기록: daily_activity_summaries에서 식사한 날 수 / 게임 횟수 합.
        summaries = await self.repo.get_summaries_between(user.user_id, start, end)
        meal_days = sum(1 for summary in summaries if summary.meal_counted)
        game_count = sum(summary.game_count for summary in summaries)

        return DashboardSummaryResponse(
            range_days=days,
            baseline_date=start,
            total_moderate_equivalent_min=total,
            activity_trend=activity_trend,
            lifestyle_records=LifestyleRecords(meal_days=meal_days, game_count=game_count),
            risk_change=await self._risk_change(user),
        )

    async def _risk_change(self, user: User) -> list[RiskChangePoint]:
        # 예측 도메인 공개 인터페이스(get_recent_predictions)를 소비한다.
        #   응답은 이미 그래프용 오래된→최신 순서이며, 모델 버전 비교 정책도 예측 도메인이 계산한다.
        #   care_stage는 기존 Android 호환을 위해 이번 단계에서만 함께 전달한다.
        history = await self.risk_service.get_recent_predictions(user, limit=self._RISK_CHANGE_LIMIT)
        return [
            RiskChangePoint(
                at=item.created_at,
                risk_score=item.risk_score,
                change_percentage_points=item.change_percentage_points,
                comparison_status=item.comparison_status,
                care_stage=item.care_stage,
            )
            for item in history.predictions
        ]

    async def get_points(self, user: User) -> PointsResponse:
        # 잔액·적립이력 모두 mission_logs.earned_points에서 파생한다.
        #   포인트는 미션 완료로만 적립되고 v6.0에서 순증가(사용 이력 제거)라
        #   별도 잔액/이력 테이블 없이 미션 로그가 단일 원천이다(중복 저장 방지).
        current_points = await self.repo.get_current_points(user.user_id)
        earn_logs = [
            PointEarnLogItem(
                earn_id=log.mission_log_id,
                earned_points=log.earned_points,
                reason=log.mission_type.value,
                created_at=log.created_at,
            )
            for log in await self.repo.get_earn_logs(user.user_id)
        ]
        return PointsResponse(current_points=current_points, earn_logs=earn_logs)

    async def get_stamps(self, user: User, month: str) -> StampsResponse:
        start, end = self._month_range(month)
        summaries = await self.repo.get_summaries_between(user.user_id, start, end)
        days = [
            StampDay(
                date=summary.summary_date,
                daily_result=summary.daily_result,
                counted_mission_count=summary.counted_mission_count,
                earned_points=summary.earned_points,
            )
            for summary in summaries
        ]
        return StampsResponse(month=month, days=days)

    @staticmethod
    def _month_range(month: str) -> tuple[date, date]:
        """`YYYY-MM` → 해당 월의 (1일, 말일). 형식이 잘못되면 400."""
        # `YYYY-MM` 형식을 엄격히 요구한다(예: `2026-7`은 거부).
        if not re.fullmatch(r"\d{4}-\d{2}", month):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="month 형식이 올바르지 않습니다. (YYYY-MM)",
            )
        try:
            year, month_num = (int(part) for part in month.split("-", 1))
            start = date(year, month_num, 1)
        except ValueError as exc:  # 형식은 맞지만 월 범위 밖(예: 2026-13/2026-00)
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="month 형식이 올바르지 않습니다. (YYYY-MM)",
            ) from exc
        last_day = calendar.monthrange(year, month_num)[1]
        return start, date(year, month_num, last_day)

    async def _available_mission_summary(
        self, user: User, level: ActivityLevel, today_summary: DailyActivitySummary | None = None
    ) -> HomeAvailableMissionSummary:
        # 미션 도메인 공개 인터페이스(get_missions)를 소비해 유형별 '수행 가능한' 미션 수를 센다.
        #   레벨을 명시해 넘겨 홈 표시 레벨과 카운트 산정 레벨을 일치시킨다.
        #   '잔여' 반영: 오늘 이미 일일 한도(daily_count_limit)를 채운 미션은 제외한다.
        #     (예: 식사 1일 1회 → 오늘 식사를 이미 카운트했으면 '가능한 미션'에서 뺀다.)
        #     오늘 카운트는 daily_activity_summaries(get_home이 이미 조회한 today_summary)에서 읽는다.
        missions = await self.mission_service.get_missions(user, mission_type=None, level=level)
        counted_today = self._counted_today_by_type(today_summary)
        counts = dict.fromkeys((MissionType.MEAL, MissionType.EXERCISE, MissionType.WALKING, MissionType.GAME), 0)
        for mission in missions:
            try:
                mission_type = MissionType(mission.mission_type)
            except ValueError:
                continue  # enum에 없는 타입은 방어적으로 건너뛴다(500 방지)
            limit = mission.daily_count_limit
            if limit is not None and counted_today.get(mission_type, 0) >= limit:
                continue  # 오늘 한도 소진 → 수행 가능 아님
            counts[mission_type] += 1
        return HomeAvailableMissionSummary(
            meal=counts[MissionType.MEAL],
            exercise=counts[MissionType.EXERCISE],
            walking=counts[MissionType.WALKING],
            game=counts[MissionType.GAME],
        )

    @staticmethod
    def _counted_today_by_type(today_summary: DailyActivitySummary | None) -> dict[MissionType, int]:
        # 오늘 유형별로 이미 카운트된 수. 요약이 없으면(오늘 활동 없음) 전부 0으로 본다.
        #   식사는 1일 1회라 bool(meal_counted)을 0/1로 환산한다.
        if today_summary is None:
            return {}
        return {
            MissionType.MEAL: 1 if today_summary.meal_counted else 0,
            MissionType.EXERCISE: today_summary.exercise_count,
            MissionType.WALKING: today_summary.walking_count,
            MissionType.GAME: today_summary.game_count,
        }
