import hashlib
from datetime import datetime, timedelta

from fastapi import HTTPException, status
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.models.enums import AuthProvider
from app.models.users import OAuthLoginNonce


class OAuthNonceRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def consume(self, provider: AuthProvider, nonce: str) -> None:
        # ID token 수명보다 긴 하루가 지난 행은 로그인 시점에 정리해 테이블을 제한한다.
        cutoff = datetime.now(config.TIMEZONE) - timedelta(days=1)
        await self.session.execute(delete(OAuthLoginNonce).where(OAuthLoginNonce.created_at < cutoff))
        nonce_hash = hashlib.sha256(nonce.encode("utf-8")).hexdigest()
        self.session.add(OAuthLoginNonce(nonce_hash=nonce_hash, provider=provider.value))
        try:
            await self.session.flush()
        except IntegrityError as exc:
            await self.session.rollback()
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="이미 사용된 로그인 정보입니다. 다시 로그인해 주세요.",
            ) from exc
