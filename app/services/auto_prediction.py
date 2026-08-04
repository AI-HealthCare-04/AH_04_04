# =====================================================================================
# 자정 자동 근육 건강 점수 계산(#422).
#
# 왜 필요한가 — 지금은 사용자가 '점수 다시 계산하기'를 눌러야만 새 예측이 생긴다. 그래서
#   변화 추이 그래프에 점이 사용자가 누른 날에만 찍히고, 며칠 안 누르면 그 기간이 통째로
#   비어 "변화"를 볼 수 없다. 매일 한 점을 서버가 만들어 추이를 연속으로 만든다.
#
# 무엇을 기준으로 도는가 — **시각이 아니라 상태**다. "00:00 에 실행"으로 두면 그 순간 컨테이너가
#   내려가 있을 때(배포·재시작·크래시) 그날 예측이 통째로 빠진다. APScheduler 는 놓친 실행을
#   스스로 알지 못해 다음 자정까지 기다린다. 그래서 조건을 "오늘 자동 예측이 없는 사용자"로 두고,
#   자정과 앱 기동 직후 모두에서 같은 작업을 돌린다. 언제 실행되든 하루 한 점이 보장된다.
#
# 사용자마다 세션을 새로 연다 — 한 사용자의 실패(예측 불가·데이터 이상)가 배치 전체를 롤백하면
#   안 된다. 커밋 단위를 사용자로 두면 실패는 그 사람만 건너뛴다.
# =====================================================================================
import logging
from dataclasses import dataclass

from fastapi import HTTPException, status
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.db.session import AsyncSessionLocal
from app.core.utils.clock import today_kst
from app.repositories.risk_prediction_repository import RiskPredictionRepository
from app.repositories.user_repository import UserRepository
from app.services.risk_prediction import RiskPredictionService

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AutoPredictionSummary:
    """배치 1회 결과. 로그·테스트가 이 값만 보면 되도록 숫자만 담는다."""

    targeted: int
    created: int
    skipped: int
    failed: int


async def run_daily_auto_predictions(
    session_factory: async_sessionmaker[AsyncSession] | None = None,
) -> AutoPredictionSummary:
    """오늘 자동 예측이 없는 사용자 전원에게 예측을 1건씩 만든다.

    **멱등이다.** 이미 오늘 자동 예측이 있으면 대상 조회에서 빠지고, 경합으로 뚫려도
    `create_auto_prediction` 이 한 번 더 확인해 None 을 돌려준다. 여러 번 돌려도 하루 한 점이다.

    [session_factory] 는 테스트가 자기 DB 를 주입하려고 열어 둔 자리다. 배치는 요청 수명주기
    밖에서 돌아 `get_db_session` 의존성을 못 쓰므로, 운영에서는 기본값(전역 팩토리)을 쓴다.
    """
    factory = session_factory or AsyncSessionLocal
    async with factory() as session:
        user_ids = await RiskPredictionRepository(session).get_user_ids_needing_auto_prediction()

    summary = AutoPredictionSummary(targeted=len(user_ids), created=0, skipped=0, failed=0)
    if not user_ids:
        logger.info("자동 예측 대상 없음 (%s)", today_kst())
        return summary

    created = skipped = failed = 0
    for user_id in user_ids:
        outcome = await _create_for_user(user_id, factory)
        if outcome is True:
            created += 1
        elif outcome is False:
            skipped += 1
        else:
            failed += 1

    summary = AutoPredictionSummary(targeted=len(user_ids), created=created, skipped=skipped, failed=failed)
    logger.info(
        "자동 예측 배치 완료 (%s) — 대상 %d, 생성 %d, 건너뜀 %d, 실패 %d",
        today_kst(),
        summary.targeted,
        summary.created,
        summary.skipped,
        summary.failed,
    )
    return summary


async def _create_for_user(user_id: int, factory: async_sessionmaker[AsyncSession]) -> bool | None:
    """True=생성, False=대상 아님(건너뜀), None=실패.

    만 65세 미만은 서비스가 422(`sarcopenia_prediction_preparing`)를 던진다 — 예측 대상이 아닌
    정상 상태이므로 실패가 아니라 '건너뜀'으로 센다. 그 외 예외는 그 사용자만 실패로 남기고
    다음 사용자로 넘어간다.
    """
    try:
        async with factory() as session:
            user = await UserRepository(session).get_user(user_id)
            if user is None:  # 배치 시작 후 탈퇴 등
                return False
            prediction = await RiskPredictionService(session).create_auto_prediction(user)
            if prediction is None:
                return False
            await session.commit()
            return True
    except HTTPException as exc:
        if exc.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT:
            return False
        logger.warning("자동 예측 실패 user_id=%s: %s", user_id, exc.detail)
        return None
    except (SQLAlchemyError, ValueError) as exc:
        logger.warning("자동 예측 실패 user_id=%s: %s", user_id, exc.__class__.__name__)
        return None
