from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.dtos.base import KstDatetime
from app.models.enums import ActivityInputSource, FeedbackReason, FeedbackResponse


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class RiskPredictionReassessRequest(BaseModel):
    activity_window_days: Literal[7, 14]


class CareStage(StrEnum):
    GOOD = "good"
    MAINTAIN = "maintain"
    ACTION_NEEDED = "action_needed"


class RiskComparisonStatus(StrEnum):
    BASELINE = "baseline"
    COMPARABLE = "comparable"
    MODEL_CHANGED = "model_changed"


class FeatureContributionResponse(BaseModel):
    """근육 점수 SHAP 기여(#406). feature=musc_days|walk_days|waist_cm(바꿀 수 있는 3개만).

    effect_on_score = 점수 방향 기여(log-odds). 양수=점수를 올리는 방향, 음수=점수를 내리는(개선 여지) 방향.
    앱은 |값|으로 막대 길이를, 부호로 색을 정한다. (나이·성별·키·체중·BMI 는 노출하지 않는다.)
    """

    feature: str
    effect_on_score: float


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
    # 근육 점수 기여도(#406): 바꿀 수 있는 3개(근력·걷기·허리)만. 예측 시점에 계산해 저장한 파생값.
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
    # 근육 점수 기여도(#406): 재평가 후에도 최신 기여도를 함께 내려 대시보드 막대를 갱신한다.
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
