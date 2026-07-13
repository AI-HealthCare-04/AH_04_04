from datetime import datetime
from typing import Annotated

from pydantic import BaseModel, ConfigDict, PlainSerializer

from app.core import config


def _serialize_kst(value: datetime) -> str:
    # 항상 KST 오프셋(+09:00)을 보장한다.
    #   - naive: MySQL 세션 tz(+09:00) 기준의 KST 벽시계 값이므로 KST를 부착한다.
    #   - tz-aware: 다른 tz(예: UTC)일 수 있으므로 astimezone으로 KST로 정규화한다.
    if value.tzinfo is None:
        value = value.replace(tzinfo=config.TIMEZONE)
    else:
        value = value.astimezone(config.TIMEZONE)
    return value.isoformat()


# 응답 타임스탬프 공용 타입 — 항상 오프셋 포함 ISO8601(+09:00)로 직렬화한다(명세 v7.8).
#   예: "2026-07-13T09:15:00+09:00". 응답의 datetime 필드는 이 타입을 쓴다(date 필드는 대상 아님).
KstDatetime = Annotated[datetime, PlainSerializer(_serialize_kst, return_type=str)]


class BaseSerializerModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)
