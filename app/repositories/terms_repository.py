# =====================================================================================
# Repository(레포지토리) 파일입니다.
# 오직 'DB에 어떻게 접근하는가'(저장)만 담당합니다.
#
# 팀 결정에 따라 약관 '목록'은 DB가 아니라 정적 catalog(app/core/terms_catalog.py)에서 오므로,
# 이 레포지토리는 오직 사용자 '동의 결과'(terms_agreements 테이블) 저장만 담당합니다.
# terms_agreements 테이블/모델은 DB PR(#3)에서 지영님이 정의한 것을 그대로 사용합니다.
# =====================================================================================

# func   : DB 내장 함수 호출용 (여기서는 현재시각 func.now())
from sqlalchemy import func

# MySQL 전용 insert입니다. .on_duplicate_key_update(...)를 붙여
# "있으면 수정, 없으면 삽입"을 쿼리 한 번(원자적)으로 처리합니다.
# → 유니크 제약 uq_terms_agreements_user_terms_type (user_id, terms_type) 를 충돌 키로 사용합니다.
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.ext.asyncio import AsyncSession

# 약관 종류 enum과 동의 기록 모델(지영님 #3 정의)을 가져옵니다.
from app.models.enums import TermsType
from app.models.terms import TermsAgreement


class TermsRepository:
    # 생성자: 이 레포지토리는 '세션'을 하나 받아서 계속 사용합니다.
    def __init__(self, session: AsyncSession):
        self.session = session

    # 사용자의 약관 동의 결과 1건을 저장합니다. 없으면 삽입(insert), 이미 있으면 갱신(update) — upsert.
    # 한 사용자는 약관 종류(terms_type)별로 최신 동의 1행만 유지합니다.
    #   → 재동의(다른 버전 동의/철회) 시 같은 행을 갱신하며, 유니크 제약 덕분에 중복 행이 생기지 않습니다.
    #   → INSERT ... ON DUPLICATE KEY UPDATE 로 처리해, 동의 버튼 연타 등 동시 요청에도 500 없이 안전합니다.
    async def upsert_agreement(
        self,
        user_id: int,
        terms_type: TermsType,
        version: str,
        is_required: bool,
        agreed: bool,
    ) -> None:
        # 1) 삽입할 값을 준비합니다. agreed_at은 DB 기본값이 없으므로 여기서 현재 시각으로 채웁니다.
        stmt = mysql_insert(TermsAgreement).values(
            user_id=user_id,
            terms_type=terms_type,
            is_required=is_required,
            agreed=agreed,
            version=version,
            agreed_at=func.now(),
        )
        # 2) (user_id, terms_type)가 이미 있어 충돌하면 → 아래 컬럼들을 이번 값으로 갱신합니다.
        #    version/is_required : 최신 약관 기준으로 갱신 (재동의 시 버전이 올라갈 수 있음)
        #    agreed              : 이번에 보낸 동의 여부
        #    agreed_at           : '마지막으로 동의/철회한 시각'으로 갱신
        stmt = stmt.on_duplicate_key_update(
            is_required=stmt.inserted.is_required,
            agreed=stmt.inserted.agreed,
            version=stmt.inserted.version,
            agreed_at=func.now(),
        )
        # 3) 실행합니다. 최종 확정(commit)은 Service에서 온보딩 상태 변경과 함께 한 번에 처리합니다.
        await self.session.execute(stmt)
        await self.session.flush()
