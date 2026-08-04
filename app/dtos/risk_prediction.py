from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.dtos.base import KstDatetime
from app.models.enums import ActivityInputSource, FeedbackReason, FeedbackResponse


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class RiskPredictionReassessRequest(BaseModel):
    """재평가 요청. 활동 창은 **7일만** 받는다(리뷰 P1).

    14 를 함께 열어 두면 8일차 게이트와 어긋난다 — 온보딩 8일차에 14 로 부르면 게이트를 통과하는데
    창의 앞 7일은 온보딩 이전이라 0 으로 채워져, 이 게이트가 막으려던 가입 직후 점수 급락이 그대로
    재현된다. 게이트를 창 길이에 맞추더라도 '같은 날 첫 호출 14 · 멱등 재호출 7' 처럼 창이 갈리면
    기존 예측이 어떤 값으로 계산됐는지 날짜만으로 되짚을 수 없어 응답의 activity_input_source 가
    다시 어긋난다.

    실제 계약이 7일뿐이라 창을 좁히는 쪽을 택했다 — 앱은 `RecordModels.kt` 에서 7 을 고정으로 보내고
    (직렬화 테스트가 고정), 자동 배치도 `DEFAULT_ACTIVITY_WINDOW_DAYS`=7 이다. 14 가 다시 필요해지면
    예측 행에 실제 사용한 창·출처를 저장한 뒤 열어야 한다.
    """

    activity_window_days: Literal[7]


class CareStage(StrEnum):
    GOOD = "good"
    MAINTAIN = "maintain"
    ACTION_NEEDED = "action_needed"


class RiskComparisonStatus(StrEnum):
    BASELINE = "baseline"
    COMPARABLE = "comparable"
    MODEL_CHANGED = "model_changed"


class BaselineChangeReason(StrEnum):
    """비교 기준이 바뀐 **이유**(#389). `comparison_status=model_changed` 만으로는 앱이
    "여기서 기준이 달라졌다"까지만 말할 수 있고 왜인지는 못 말한다 — 사용자는 그걸 고장으로 읽는다.

    모델 버전·변형 자체는 비노출 계약이라(`test_risk_history_nondisclosure`) 내부 식별자 대신
    **사용자가 이해할 수 있는 사유**로 추상화해 내려준다. 단정할 수 없는 전환은 None 이다.
    """

    WAIST_ADDED = "waist_added"  # 허리둘레가 들어와 허리 포함 모델로 바뀜
    WAIST_REMOVED = "waist_removed"  # 허리둘레가 빠져 허리 제외 모델로 바뀜
    COHORT_UPDATED = "cohort_updated"  # 또래 비교표(코호트) 버전 갱신


class FeatureContributionResponse(BaseModel):
    """근육 점수 SHAP 기여(#406). feature=musc_days|walk_days|waist_cm(바꿀 수 있는 3개만).

    ⚠️ effect_on_score_log_odds 는 **log-odds 단위이며 점수(0~100)가 아니다.** 앱은 |값|으로 막대
    길이를, 부호로 색·방향 문구를 정하고 **수치 자체는 화면에 노출하지 않는다**(#406 P2 — "허리 때문에
    1.29점 깎였다"는 근거 없는 원인 단정 방지). 양수=점수를 올리는 방향, 음수=내리는(개선 여지) 방향.
    (나이·성별·키·체중·BMI 는 노출하지 않는다.)
    """

    feature: str
    effect_on_score_log_odds: float


class RiskPredictionResponse(BaseModel):
    prediction_id: int
    profile_id: int
    model_variant: str
    risk_score: float = Field(ge=0, le=1)
    muscle_score: int | None = Field(default=None, ge=0, le=100)
    score_band: str | None = None
    cohort_version: str | None = None
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."
    # 근육 점수 기여도(#406): 바꿀 수 있는 3개(근력·걷기·허리)만. 예측(create) 시점에 계산해 이 응답에만
    #   싣고 서버에는 저장하지 않는다(#408 최소화 — 역산 가능한 허리둘레 복제 방지). /me/latest 는 빈 목록이라
    #   앱이 이 응답을 로컬 캐시해 대시보드 막대를 그린다. 캐시가 없으면 카드를 숨긴다.
    contributions: list[FeatureContributionResponse] = Field(default_factory=list)


class RiskPredictionCreateResponse(RiskPredictionResponse):
    onboarding_status: str


class CohortDistributionResponse(BaseModel):
    """또래 분포 병합 차트(#193) 데이터. 온보딩 결과 위험도 카드가 소비한다.

    probability=사용자 근감소증 추정 확률(0~1), lower_count=100명 환산 '나보다 위험이 낮은 사람 수'
    (서버가 quantiles 선형보간으로 산출, [1,99] 클램프), density=곡선 좌표 [[x(확률), y(상대밀도 0~100)]].
    """

    probability: float = Field(ge=0, le=1)
    sex: Literal["male", "female"]
    age_label: str
    n: int = Field(ge=0)
    lower_count: int = Field(ge=1, le=99)
    quantiles: list[float]
    density: list[list[float]]
    density_method: str
    cohort_version: str | None = None
    model_version: str | None = None


class RiskPredictionReassessResponse(BaseModel):
    """재평가 응답. 하루 1회 정책(#388)의 결과를 앱이 안내할 수 있게 두 필드를 함께 내려준다.

    `recalculated=False` 면 오늘 이미 재평가한 사용자라 **기존 예측을 그대로 돌려준 것**이다
    (429 대신 200 + 멱등 — 앱이 오류 처리 없이 결과를 그대로 보여주면 된다).
    `next_available_at` 은 다음 재평가가 가능해지는 시각(다음 KST 자정)이다.
    """

    profile_id: int
    prediction_id: int
    risk_score: float = Field(ge=0, le=1)
    muscle_score: int | None = Field(default=None, ge=0, le=100)
    score_band: str | None = None
    cohort_version: str | None = None
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."
    # 근육 점수 기여도(#406): recalculated=True(이번 호출로 새 계산)일 때만 실어 대시보드 막대를 갱신한다.
    #   recalculated=False(오늘 이미 재평가한 기존 예측 반환)면 재계산하지 않으므로 빈 목록이다 — 이때
    #   앱은 기존 로컬 캐시를 유지한다(빈 목록을 받아도 캐시를 지우지 않는다).
    contributions: list[FeatureContributionResponse] = Field(default_factory=list)
    activity_input_source: ActivityInputSource = ActivityInputSource.SERVICE_LOG
    # 하루 1회 정책(#388). True=이번 호출로 새로 계산, False=오늘 이미 계산해 기존 예측을 반환.
    recalculated: bool = True
    # 다음 재평가 가능 시각(KST 자정). 앱이 "내일 다시 계산할 수 있어요" 안내에 쓴다.
    next_available_at: KstDatetime | None = None


class RiskPredictionHistoryItem(BaseModel):
    # 연속 위험도는 공개하되 내부 등급·모델 식별자는 비노출한다.
    # 모델 버전 비교는 서버가 comparison_status로 추상화한다.
    prediction_id: int
    created_at: KstDatetime
    risk_score: float = Field(ge=0, le=1)
    muscle_score: int | None = Field(default=None, ge=0, le=100)
    score_band: str | None = None
    cohort_version: str | None = None
    change_percentage_points: float | None = Field(ge=-100, le=100)
    comparison_status: RiskComparisonStatus
    # 기준이 바뀐 이유(#389). None = 직전이 없거나(첫 예측) 사유를 단정할 수 없는 전환.
    #   내부 식별자가 아니라 사용자가 이해할 수 있는 사유로만 내려준다.
    baseline_change_reason: BaselineChangeReason | None = None
    # 직전 예측과 **다른 신체 정보 스냅샷**으로 계산됐는가(#389 C). 점수가 내려간 이유를
    #   활동 부족으로 단정하지 않으려면 앱이 이걸 알아야 한다 — 허리둘레처럼 모델을 바꾸지 않는
    #   변경(체중·키)은 경계도 사유도 만들지 않지만 점수는 달라진다.
    #   재평가는 프로필을 복제하지 않으므로(#408 A+3) 값이 달라졌다면 사용자가 내 정보를 고친 것이다.
    profile_changed: bool = False
    # 기존 Android 계약 호환용. 연속형 화면 전환 후 제거 또는 내부 한정 예정이다.
    care_stage: CareStage


class RiskPredictionHistoryResponse(BaseModel):
    predictions: list[RiskPredictionHistoryItem]


class PredictionFeedbackRequest(BaseModel):
    """예측 결과 체감 피드백(#357). reason 은 '다르게 느껴져요' 선택 시 선택 입력이다."""

    response: FeedbackResponse
    reason: FeedbackReason | None = None

    @model_validator(mode="after")
    def _reason_only_for_different(self) -> "PredictionFeedbackRequest":
        # too_high/too_low/other 는 'different'의 불일치 사유다(#357 계약, 리뷰 반영).
        #   similar/unsure 에 사유가 붙으면 집계의 사유 분포가 오염되므로 422 로 거른다.
        if self.reason is not None and self.response is not FeedbackResponse.DIFFERENT:
            raise ValueError("reason 은 response='different' 일 때만 보낼 수 있습니다.")
        return self


class PredictionFeedbackResponse(BaseModel):
    prediction_id: int
    response: FeedbackResponse
    reason: FeedbackReason | None = None
    created_at: KstDatetime
