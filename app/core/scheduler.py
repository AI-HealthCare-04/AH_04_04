# =====================================================================================
# 자정 자동 예측 스케줄러(#422).
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
# 워커가 여러 개면 같은 작업이 동시에 뜬다 — 지금은 uvicorn 단일 워커라 문제되지 않고,
#   설령 겹쳐도 대상 조회 + create_auto_prediction 의 재확인으로 하루 한 점은 유지된다.
# =====================================================================================
import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

from app.core import config
from app.services.auto_prediction import run_daily_auto_predictions

logger = logging.getLogger(__name__)

_JOB_ID = "daily_auto_predictions"

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


async def catch_up_on_startup() -> None:
    """기동 직후 오늘 치를 메운다 — 자정에 앱이 내려가 있었던 경우의 유일한 복구 경로다.

    실패해도 앱 기동을 막지 않는다. 이 작업이 안 돌아도 서비스는 정상 동작하고, 다음 자정이나
    다음 기동에서 다시 시도된다.
    """
    try:
        await _run_and_log()
    except Exception:  # noqa: BLE001 - 기동을 막지 않는 것이 이 호출의 목적이다
        logger.exception("기동 시 자동 예측 보정 실패 — 다음 실행에서 재시도한다")


async def _run_and_log() -> None:
    summary = await run_daily_auto_predictions()
    if summary.failed:
        logger.warning("자동 예측에 실패한 사용자가 있다 — 실패 %d명", summary.failed)
