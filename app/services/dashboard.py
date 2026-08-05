import calendar
import re
from datetime import date, timedelta

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.dashboard import (
    ChallengeTotalsResponse,
    ChallengeTypeTotal,
    DashboardPredictionInputs,
    HomeAvailableMissionSummary,
    HomeLatestPrediction,
    HomeResponse,
    HomeStreak,
    HomeTodaySummary,
    HomeTodayWalking,
    HomeUser,
    PointBalanceResponse,
    ScoreSimulationResponse,
    StampDay,
    StampsResponse,
    WalkingDailyResponse,
    WalkingDayPoint,
)
from app.models.dashboard import DailyActivitySummary
from app.models.enums import DailyResult, MissionType
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.services.mission import MissionService
from app.services.risk_prediction import RiskPredictionService
from app.services.streak import compute_current_streak


class DashboardService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = DashboardRepository(session)
        self.health_repo = HealthProfileRepository(session)
        self.mission_service = MissionService(session)
        self.risk_service = RiskPredictionService(session)

    async def get_prediction_inputs(self, user: User) -> DashboardPredictionInputs:
        """근감소증 예측 대시보드(#193) 개인화 초기값. 최신 health_profile(신체) + 최근 7일
        걷기/운동 요일 수(daily_activity_summaries)를 합쳐 준다. 프로필 없으면 신체는 null."""
        as_of = today_kst()
        start = as_of - timedelta(days=6)  # 최근 7일(오늘 포함)
        profile = await self.health_repo.get_latest_profile(user.user_id)
        walk_days, musc_days = await self.repo.count_active_days(user.user_id, start, as_of)
        return DashboardPredictionInputs(
            sex=(profile.sex.value if profile else None),
            birth_date=(profile.birth_date if profile else None),
            height_cm=(float(profile.height_cm) if profile else None),
            weight_kg=(float(profile.weight_kg) if profile else None),
            waist_cm=(float(profile.waist_cm) if profile and profile.waist_cm is not None else None),
            walk_days=min(walk_days, 7),
            musc_days=min(musc_days, 5),
        )

    async def get_home(self, user: User) -> HomeResponse:
        as_of_date = today_kst()
        current_points = await self.repo.get_current_points(user.user_id)
        summary = await self.repo.get_today_summary(user.user_id)
        streak = compute_current_streak(
            await self.repo.get_counted_summary_dates(user.user_id, as_of_date),
            as_of_date=as_of_date,
        )
        available = await self._available_mission_summary(user, summary)
        latest_prediction = await self._latest_prediction(user)
        # 오늘 걷기 누적 실적(분·걸음). 걷기 완료 응답과 같은 원천(#65)을 재사용 → 홈·완료 화면 값 일치.
        #   목표(분)는 여기 넣지 않는다(GET /missions가 단일 원천). 걷기 없으면 (0.0, 0).
        total_walking_min, total_walking_steps = await self.mission_service.get_today_walking_totals(user)
        return HomeResponse(
            user=HomeUser(nickname=user.nickname),
            point_balance=PointBalanceResponse(current_points=current_points),
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
            streak=HomeStreak(
                current_days=streak.current_days,
                completed_today=streak.completed_today,
                as_of_date=as_of_date,
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

    _WALKING_DAILY_MAX_DAYS = 31

    async def get_walking_daily(self, user: User, days: int) -> WalkingDailyResponse:
        # 최근 days일(오늘 포함) 걷기 걸음·분. 걷기 없는 날도 0으로 채워 앱 막대 바인딩을 단순화한다(#기록탭 §5.3).
        if days < 1 or days > self._WALKING_DAILY_MAX_DAYS:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"days는 1~{self._WALKING_DAILY_MAX_DAYS} 사이여야 합니다.",
            )
        end = today_kst()
        start = end - timedelta(days=days - 1)
        per_day = await self.repo.get_walking_daily(user.user_id, start, end)
        points = []
        for offset in range(days):
            day = start + timedelta(days=offset)
            steps, minutes = per_day.get(day, (0, 0.0))
            points.append(WalkingDayPoint(date=day, steps=steps, minutes=round(minutes, 1)))
        return WalkingDailyResponse(days=points)

    async def get_score_simulation(self, user: User) -> ScoreSimulationResponse:
        # what-if 점수 곡선(#기록탭 §4). 예측 도메인 서비스에 위임(예측기·코호트 파생의 단일 출처).
        return await self.risk_service.get_score_simulation(user)

    async def get_challenge_totals(self, user: User) -> ChallengeTotalsResponse:
        # 유형별 완료 일수(#기록탭 §5.4 — 선호도, 모든 유형 하루 1회 상한). 0회 유형도 포함해 앱이 범례를 회색으로 표시.
        counts = await self.repo.get_challenge_totals(user.user_id)
        ordered = (MissionType.WALKING, MissionType.EXERCISE, MissionType.MEAL, MissionType.GAME)
        by_type = [ChallengeTypeTotal(mission_type=t.value, count=counts.get(t, 0)) for t in ordered]
        return ChallengeTotalsResponse(total=sum(item.count for item in by_type), by_type=by_type)

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
        self, user: User, today_summary: DailyActivitySummary | None = None
    ) -> HomeAvailableMissionSummary:
        # 미션 도메인 공개 인터페이스(get_missions)를 소비해 유형별 '수행 가능한' 미션 수를 센다.
        #   '잔여' 반영: 오늘 이미 일일 한도(daily_count_limit)를 채운 미션은 제외한다.
        #     (예: 식사 1일 1회 → 오늘 식사를 이미 카운트했으면 '가능한 미션'에서 뺀다.)
        #     오늘 카운트는 daily_activity_summaries(get_home이 이미 조회한 today_summary)에서 읽는다.
        missions = await self.mission_service.get_missions(user, mission_type=None)
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
