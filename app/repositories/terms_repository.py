# =====================================================================================
# Repository(레포지토리) 파일입니다.
# 오직 'DB에 어떻게 접근하는가'(조회/저장)만 담당합니다.
# 비즈니스 규칙(검증, 상태 변경 등)은 여기 두지 않고 Service 계층에서 처리합니다.
# → 이렇게 역할을 나누면 코드가 깔끔하고 테스트하기 쉬워집니다.
# =====================================================================================

# Sequence: "읽기 전용 리스트" 같은 타입 힌트입니다. list보다 넓은 의미로, 반환값이 여러 개임을 표현합니다.
from collections.abc import Sequence

# select    : SELECT 문(조회 쿼리)을 만들 때 사용
# func      : DB 내장 함수 호출용 (여기서는 현재시각 func.now())
# tuple_    : 여러 컬럼을 하나의 '묶음'으로 보고 IN 조건을 걸 때 사용 (예: (종류, 버전) 쌍으로 검색)
from sqlalchemy import func, select, tuple_

# MySQL 전용 insert입니다. 일반 insert와 달리 .on_duplicate_key_update(...)를 붙일 수 있어
# "있으면 수정, 없으면 삽입"을 쿼리 한 번(원자적)으로 처리할 수 있습니다.
from sqlalchemy.dialects.mysql import insert as mysql_insert

# AsyncSession: 비동기 방식으로 DB와 대화하는 '세션'입니다. 모든 쿼리는 이 세션을 통해 실행됩니다.
from sqlalchemy.ext.asyncio import AsyncSession

# 위에서 정의한 약관 관련 모델(테이블)들을 가져옵니다.
from app.models.terms import Term, TermsAgreement


class TermsRepository:
    # 생성자: 이 레포지토리는 '세션'을 하나 받아서 계속 사용합니다.
    def __init__(self, session: AsyncSession):
        self.session = session

    # 현재 노출 중인(is_active=True) 약관 목록을 종류 순서로 정렬해서 모두 가져옵니다.
    async def list_active_terms(self) -> Sequence[Term]:
        # select(Term)               : terms 테이블에서 행을 조회
        # .where(is_active == True)  : 활성 상태인 것만
        # .order_by(terms_type)      : 약관 종류 기준으로 정렬(항상 같은 순서로 보여주기 위함)
        stmt = select(Term).where(Term.is_active.is_(True)).order_by(Term.terms_type)
        # scalars(): 각 행에서 Term 객체 하나씩만 뽑아줍니다. (튜플이 아니라 모델 객체로)
        result = await self.session.scalars(stmt)
        # .all(): 조회 결과 전체를 리스트 형태로 반환합니다.
        return result.all()

    # 주어진 (종류, 버전) 쌍들에 해당하는 약관을 '활성/비활성 상관없이' 모두 찾아옵니다.
    # 용도: 사용자가 보낸 약관이 "아예 없는 값"인지 "예전에 있었지만 지금은 비활성(구버전)"인지 구분하기 위함.
    async def get_terms_by_type_version(self, keys: Sequence[tuple[str, str]]) -> Sequence[Term]:
        # 빈 목록으로 IN 쿼리를 만들면 비효율/오류가 될 수 있어, 빈 경우엔 바로 빈 리스트를 돌려줍니다.
        if not keys:
            return []
        # tuple_(A, B).in_([(a1,b1), (a2,b2)]) → "(A,B) 조합이 이 목록 중 하나인 행"을 찾습니다.
        stmt = select(Term).where(tuple_(Term.terms_type, Term.version).in_(keys))
        result = await self.session.scalars(stmt)
        return result.all()

    # 동의 기록을 저장합니다. 없으면 삽입(insert), 이미 있으면 갱신(update) — 이른바 upsert.
    # MySQL의 INSERT ... ON DUPLICATE KEY UPDATE 를 사용해 '쿼리 한 번'으로 원자적으로 처리합니다.
    # → 같은 사용자가 동의 버튼을 빠르게 두 번 눌러 두 요청이 동시에 들어와도
    #   유니크 제약(uq_terms_agreements_user_term) 충돌로 500이 나지 않고 안전하게 갱신됩니다.
    async def upsert_agreement(self, user_id: int, term_id: int, is_agreed: bool) -> None:
        # 1) 삽입하려는 값을 준비합니다. (신규 삽입이면 agreed_at은 컬럼의 server_default=now()로 채워짐)
        stmt = mysql_insert(TermsAgreement).values(
            user_id=user_id,
            term_id=term_id,
            is_agreed=is_agreed,
        )
        # 2) 이미 (user_id, term_id) 기록이 있어 충돌하면 → 아래 값들만 갱신합니다.
        #    is_agreed : 이번에 보낸 동의 여부로 갱신
        #    agreed_at : '마지막으로 동의/철회한 시각'이 되도록 현재 시각으로 갱신 (리뷰 2번 반영)
        stmt = stmt.on_duplicate_key_update(
            is_agreed=stmt.inserted.is_agreed,
            agreed_at=func.now(),
        )
        # 3) 쿼리를 실행합니다. flush()로 이 변경을 DB로 내보내되, 최종 확정(commit)은 Service에서 일괄 처리합니다.
        await self.session.execute(stmt)
        await self.session.flush()
