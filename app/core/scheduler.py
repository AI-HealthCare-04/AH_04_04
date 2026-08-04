# =====================================================================================
# 자정 자동 예측 스케줄러.
#
# 인프로세스(APScheduler)로 둔 이유 — EC2 프리티어 1대에 mysql·fastapi 두 컨테이너만 띄우고
#   있다. cron 컨테이너를 늘리면 docker-compose 와 배포 스크립트를 함께 손봐야 하고,
#   GitHub Actions 로 빼면 관리자 전용 엔드포인트와 토큰 관리가 새로 생긴다. 하루 한 번,
#   사용자 수십 명 규모의 작업이라 앱 안에서 도는 편이 운영 면이 가장 작다.
#
# ⚠️ 인프로세스의 약점과 대처 — 앱이 내려가 있으면 타이머도 없다. 배포 때마다 컨테이너가
#   재시작되므로 하필 그때가 자정이면 그날 실행이 통째로 빠지고, APScheduler 는 놓친 실행을
#   스스로 복구하지 않는다. 그래서 **기동 직후에도 같은 작업을 한 번 돌린다.** 작업 자체가
#   "오늘 자동 예측이 없는 사용자"만 고르는 멱등 연산이라 중복 실행이 안전하다.
#
# ⚠️ 중복 실행은 잠금으로 막는다(리뷰 P1) — 단일 워커여도 겹친다. 기동 보정과 00:05 cron 은
#   서로 다른 작업이라 max_instances 가 막지 못하고, 00:05 직전에 기동하면 둘이 동시에 돈다.
#   대상 조회 + 재확인만으로는 check-then-insert 경쟁이 남으므로, 사용자 단위 FOR UPDATE 잠금을
#   커밋까지 유지해 하루 한 점을 DB 수준에서 보장한다(create_auto_prediction).
# =====================================================================================
import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler  # type: ignore[import-untyped]
from apscheduler.triggers.cron import CronTrigger  # type: ignore[import-untyped]
from apscheduler.triggers.date import DateTrigger  # type: ignore[import-untyped]

from app.core import config
from app.core.utils.clock import now_kst
from app.services.auto_prediction import run_daily_auto_predictions

logger = logging.getLogger(__name__)

_JOB_ID = "daily_auto_predictions"
_CATCH_UP_JOB_ID = "daily_auto_predictions_catch_up"

_scheduler: AsyncIOScheduler | None = None


def start_scheduler() -> AsyncIOScheduler:
    """자정(KST) 작업을 등록하고 스케줄러를 켠다. 이미 켜져 있으면 그대로 돌려준다."""
    global _scheduler
    if _scheduler is not None:
        return _scheduler

    scheduler = AsyncIOScheduler(timezone=config.TIMEZONE)
    scheduler.add_job(
        _run_and_log,
        trigger=CronTrigger(hour=0, minute=5, timezone=config.TIMEZONE),
        id=_JOB_ID,
        # 실행이 밀렸을 때 같은 작업이 겹쳐 돌지 않게 한다. 작업 자체가 멱등이지만 DB 부하를 아낀다.
        max_instances=1,
        # 컨테이너가 잠깐 멈춰 실행 시각을 지나쳤어도 1시간 안이면 따라잡는다. 그보다 늦은 경우는
        #   다음 기동의 catch-up 이 처리한다.
        misfire_grace_time=3600,
        coalesce=True,
    )
    scheduler.start()
    _scheduler = scheduler
    logger.info("자동 예측 스케줄러 시작 — 매일 00:05 KST")
    return scheduler


def shutdown_scheduler() -> None:
    """앱 종료 시 타이머를 정리한다. 진행 중인 작업은 기다리지 않는다(다음 기동이 메운다)."""
    global _scheduler
    if _scheduler is None:
        return
    _scheduler.shutdown(wait=False)
    _scheduler = None
    logger.info("자동 예측 스케줄러 종료")


def schedule_startup_catch_up() -> None:
    """기동 직후 오늘 치를 메우는 작업을 **스케줄러에 맡긴다**(리뷰 P2).

    자정에 앱이 내려가 있었던 경우의 유일한 복구 경로지만, lifespan 에서 `await` 하면 배치가
    끝날 때까지 서버가 준비 상태가 되지 않는다. 배포의 `/health` 확인은 `--retry 12
    --retry-delay 5` 로 **약 60초**만 기다리므로, 사용자가 늘거나 DB 가 느려지면 헬스체크
    타임아웃 → 재시작 루프가 된다. 스케줄러에 즉시 실행 작업으로 넘겨 기동 경로에서 뗀다.

    `_JOB_ID` 와 다른 id 를 쓰되 같은 함수를 부른다 — 작업 자체가 멱등이고, 사용자 단위 잠금이
    자정 작업과의 중복 저장을 막는다(리뷰 P1).
    """
    scheduler = start_scheduler()
    scheduler.add_job(
        _run_and_log,
        trigger=DateTrigger(run_date=now_kst()),
        id=_CATCH_UP_JOB_ID,
        # 이전 기동의 보정이 아직 돌고 있으면 새로 넣지 않는다(재시작이 잦을 때의 중복 방지).
        replace_existing=True,
        misfire_grace_time=None,
    )
    logger.info("기동 보정 작업 등록 — 즉시 실행")


async def _run_and_log() -> None:
    """작업 본체. 예외가 스케줄러 밖으로 나가 로그만 남기고 사라지지 않게 여기서 잡는다."""
    try:
        summary = await run_daily_auto_predictions()
    except Exception:  # noqa: BLE001 - 한 번의 실패가 다음 실행을 막지 않아야 한다
        logger.exception("자동 예측 배치 실패 — 다음 실행에서 재시도한다")
        return
    if summary.failed:
        logger.warning("자동 예측에 실패한 사용자가 있다 — 실패 %d명", summary.failed)
