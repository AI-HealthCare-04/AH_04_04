# =====================================================================================
# Service(서비스) 파일입니다.
# 실제 '비즈니스 규칙'을 담당합니다. 예)
#   - 필수 약관에 모두 동의했는지 검사
#   - 구버전 약관 제출을 걸러 최신 약관 재동의로 유도
#   - 동의 처리 후 사용자의 온보딩 상태를 다음 단계로 넘기기
# DB 접근은 Repository에게 맡기고, 여기서는 '무엇을 해야 하는가'에 집중합니다.
# =====================================================================================

from collections.abc import Sequence

# HTTPException : 잘못된 요청일 때 특정 HTTP 상태코드(400/409 등)로 응답을 끊고 싶을 때 던집니다.
#                 (여기서 던진 detail 메시지는 main.py의 공통 핸들러가 {"error_detail": ...}로 감쌉니다.)
# status        : 상태코드를 숫자 대신 읽기 쉬운 이름으로 쓰게 해줍니다.
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

# 요청 데이터의 형태(DTO)를 가져옵니다.
from app.dtos.terms import TermsAgreementRequest

# 약관 모델과, 사용자 모델/온보딩 상태 열거형을 가져옵니다.
from app.models.terms import Term
from app.models.users import OnboardingStatus, User

# DB 접근은 이 레포지토리를 통해서 합니다.
from app.repositories.terms_repository import TermsRepository


class TermsService:
    # 생성자: 세션을 받아 두고, 그 세션으로 동작하는 레포지토리를 미리 만들어 둡니다.
    def __init__(self, session: AsyncSession):
        self.session = session
        self.terms_repo = TermsRepository(session)

    # [GET /terms] 현재 노출 중인 약관 목록을 그대로 돌려줍니다.
    # 별다른 규칙이 없으므로 레포지토리 결과를 그대로 전달합니다.
    async def get_active_terms(self) -> Sequence[Term]:
        return await self.terms_repo.list_active_terms()

    # [POST /users/me/agreements] 약관 동의 제출을 처리합니다.
    # 반환값: 상태가 갱신된 User 객체 (라우터에서 onboarding_status를 꺼내 응답합니다.)
    async def agree_terms(self, user: User, data: TermsAgreementRequest) -> User:
        # 1) 현재 활성 약관을 모두 불러옵니다. (검증의 '정답지' 역할)
        active_terms = await self.terms_repo.list_active_terms()

        # 2) (종류, 버전) → 활성 약관 객체 로 빠르게 찾기 위한 딕셔너리를 만듭니다.
        #    예) {("service", "1.0"): <Term 객체>, ...}
        #    term.terms_type 은 StrEnum이라 str()로 순수 문자열("service")로 변환해 키로 씁니다.
        active_by_key = {(str(term.terms_type), term.version): term for term in active_terms}

        # 3) 클라이언트가 보낸 각 동의 항목을 (종류, 버전) → 동의여부 딕셔너리로 정리합니다.
        submitted = {(item.terms_type, item.version): item.agreed for item in data.agreements}

        # 4) 활성 목록에 없는 (종류, 버전)을 걸러냅니다. 이때 두 경우를 '구분'해서 다르게 응답합니다.
        #    - 구버전(비활성): 예전엔 있었지만 지금은 is_active=False → 최신 약관으로 재동의하도록 409로 안내
        #    - 존재한 적 없음: DB에 아예 없는 값 → 변조/오류로 보고 400으로 거부
        not_active_keys = set(submitted) - set(active_by_key)
        if not_active_keys:
            # 활성/비활성 상관없이 DB에 실제로 존재하는 (종류, 버전)을 조회합니다.
            existing_terms = await self.terms_repo.get_terms_by_type_version(list(not_active_keys))
            existing_keys = {(str(term.terms_type), term.version) for term in existing_terms}

            # DB에 아예 없는 값(존재한 적 없음)이 하나라도 있으면 → 잘못된 요청(400)으로 먼저 거부합니다.
            # (구버전 안내보다 우선. 정상적인 '오래된 화면' 사용자는 없는 값을 보낼 수 없기 때문입니다.)
            never_existed = not_active_keys - existing_keys
            if never_existed:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="존재하지 않는 약관이 포함되어 있습니다.",
                )

            # 남은 건 '존재하지만 지금은 비활성'인 구버전뿐 → 409로 최신 약관 재동의를 유도합니다.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="약관이 변경되었습니다. 최신 약관을 다시 확인한 뒤 동의해 주세요.",
            )

        # 5) '필수' 약관은 반드시 동의(agreed=True)로 들어와야 합니다.
        #    하나라도 빠지거나 false면 400으로 막습니다. (명세서 에러 메시지와 동일)
        for key, term in active_by_key.items():
            if term.is_required and not submitted.get(key, False):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="필수 약관에 동의해야 합니다.",
                )

        # 6) 검증을 통과했으니, 보낸 항목들을 하나씩 DB에 저장(있으면 갱신)합니다.
        for key, agreed in submitted.items():
            term = active_by_key[key]  # 위에서 유효성 확인이 끝났으므로 반드시 존재합니다.
            await self.terms_repo.upsert_agreement(user_id=user.user_id, term_id=term.term_id, is_agreed=agreed)

        # 7) 아직 온보딩 첫 단계(PENDING)라면, 약관 동의를 마쳤으므로 다음 단계로 올려줍니다.
        #    (이미 더 진행된 사용자라면 되돌리지 않도록 PENDING일 때만 변경)
        if user.onboarding_status == OnboardingStatus.PENDING:
            user.onboarding_status = OnboardingStatus.TERMS_AGREED

        # 8) 지금까지의 모든 변경(동의 기록 + 상태 변경)을 DB에 최종 확정(commit)합니다.
        await self.session.commit()
        # refresh: DB에 저장된 최신 값을 user 객체에 다시 채워 넣습니다.
        await self.session.refresh(user)
        return user
