# =====================================================================================
# Service(서비스) 파일입니다.
# 실제 '비즈니스 규칙'을 담당합니다. 예)
#   - 제출된 약관이 현재 유효/최신인지 검증 (정적 catalog 기준)
#   - 필수 약관에 모두 동의했는지 검사
#   - 구버전 약관 제출 시 최신 약관 재동의로 유도(409)
#   - 동의 처리 후 사용자의 온보딩 상태를 다음 단계로 넘기기
#
# 약관 '목록'은 DB가 아니라 정적 catalog에서 오고, '동의 결과'만 Repository를 통해 DB에 저장합니다.
# =====================================================================================

from collections.abc import Sequence

# HTTPException : 잘못된 요청일 때 특정 상태코드(400/409)로 응답을 끊습니다.
#                 (여기서 던진 detail은 main.py 공통 핸들러가 {"error_detail": ...}로 감쌉니다.)
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

# 정적 약관 카탈로그: 현재 유효한 약관 목록(TERMS_CATALOG)과 종류별 조회 딕셔너리(CATALOG_BY_TYPE).
from app.core.terms_catalog import CATALOG_BY_TYPE, TERMS_CATALOG, TermSpec
from app.dtos.terms import TermsAgreementRequest

# 사용자 모델/온보딩 상태 열거형.
from app.models.users import OnboardingStatus, User

# 동의 결과 저장은 이 레포지토리를 통해서 합니다.
from app.repositories.terms_repository import TermsRepository


class TermsService:
    # 생성자: 세션을 받아 두고, 그 세션으로 동작하는 레포지토리를 미리 만들어 둡니다.
    def __init__(self, session: AsyncSession):
        self.session = session
        self.terms_repo = TermsRepository(session)

    # [GET /terms] 현재 유효한 약관 목록을 정적 catalog에서 그대로 돌려줍니다. (DB 접근 없음)
    async def get_active_terms(self) -> Sequence[TermSpec]:
        return TERMS_CATALOG

    # [POST /users/me/agreements] 약관 동의 제출을 처리합니다.
    # 반환값: 상태가 갱신된 User 객체 (라우터에서 onboarding_status를 꺼내 응답합니다.)
    async def agree_terms(self, user: User, data: TermsAgreementRequest) -> User:
        # 1) 클라이언트가 보낸 각 항목을 (종류 → 항목)으로 정리합니다.
        #    같은 종류가 여러 번 오면 마지막 값이 남습니다(MVP에선 허용).
        submitted = {item.terms_type: item for item in data.agreements}

        # 2) 제출된 약관들을 catalog 기준으로 검증합니다.
        #    - catalog에 없는 종류        → 존재하지 않는 약관(변조/오류)  → 400
        #    - 종류는 있으나 버전이 다름   → 구버전(앱 화면이 오래됨)       → 409 재동의 유도
        unknown = [t for t in submitted if t not in CATALOG_BY_TYPE]
        if unknown:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="존재하지 않는 약관이 포함되어 있습니다.",
            )

        stale = [t for t, item in submitted.items() if item.version != CATALOG_BY_TYPE[t].version]
        if stale:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="약관이 변경되었습니다. 최신 약관을 다시 확인한 뒤 동의해 주세요.",
            )

        # 3) '필수' 약관은 반드시 동의(agreed=True)로 들어와야 합니다.
        #    하나라도 빠지거나 false면 400으로 막습니다. (명세서 에러 메시지와 동일)
        for spec in TERMS_CATALOG:
            if spec.is_required:
                item = submitted.get(str(spec.terms_type))
                if item is None or not item.agreed:
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="필수 약관에 동의해야 합니다.",
                    )

        # 4) 검증 통과 → 각 동의 결과를 DB(terms_agreements)에 저장(있으면 갱신)합니다.
        #    version/is_required는 신뢰할 수 있는 catalog 값으로 저장합니다(클라이언트 값 그대로 쓰지 않음).
        for terms_type_str, item in submitted.items():
            spec = CATALOG_BY_TYPE[terms_type_str]
            await self.terms_repo.upsert_agreement(
                user_id=user.user_id,
                terms_type=spec.terms_type,
                version=spec.version,
                is_required=spec.is_required,
                agreed=item.agreed,
            )

        # 5) 아직 온보딩 첫 단계(PENDING)라면, 약관 동의를 마쳤으므로 다음 단계로 올려줍니다.
        #    (이미 더 진행된 사용자라면 되돌리지 않도록 PENDING일 때만 변경)
        if user.onboarding_status == OnboardingStatus.PENDING:
            user.onboarding_status = OnboardingStatus.TERMS_AGREED

        # 6) 지금까지의 모든 변경(동의 기록 + 상태 변경)을 DB에 최종 확정(commit)합니다.
        await self.session.commit()
        await self.session.refresh(user)
        return user
