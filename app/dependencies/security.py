from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.models.users import User
from app.repositories.user_repository import UserRepository
from app.services.jwt import JwtService

# auto_error=False: 자격증명 부재 시 기본 영문 메시지("Not authenticated") 대신
# 명세 규격 한글 메시지를 우리가 직접 던지기 위해 자동 에러를 끈다.
security = HTTPBearer(auto_error=False)


async def get_request_user(
    credential: Annotated[HTTPAuthorizationCredentials | None, Depends(security)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> User:
    if credential is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="인증이 필요합니다.")
    token = credential.credentials
    verified = JwtService().verify_jwt(token=token, token_type="access")
    user_id = verified.payload["user_id"]
    user = await UserRepository(session).get_user(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="인증이 필요합니다.")
    return user
