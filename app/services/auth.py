from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.auth import GoogleLoginRequest
from app.models.users import AuthProvider, User
from app.repositories.user_repository import UserRepository
from app.services.jwt import JwtService


class AuthService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.user_repo = UserRepository(session)
        self.jwt_service = JwtService()

    async def login_with_google(self, data: GoogleLoginRequest) -> tuple[User, str]:
        # MVP scaffold only:
        # This does not verify Google OAuth yet.
        # Until Android OAuth is ready, the incoming value is temporarily treated as a Google social_id
        # so we can connect the API route, users table flow, and JWT issuing flow early.
        # Replace this with real Google id_token verification before judge demo or release.
        social_id = data.authorization_code
        nickname = "회원님"

        user = await self.user_repo.get_by_provider_social_id(AuthProvider.GOOGLE, social_id)
        if user is None:
            user = await self.user_repo.create_google_user(social_id=social_id, nickname=nickname)
        else:
            await self.user_repo.update_last_login(user)

        await self.session.commit()
        token = self.jwt_service.create_access_token(user)
        return user, str(token)
