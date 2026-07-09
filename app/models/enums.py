from enum import StrEnum


def enum_values(enum_cls: type[StrEnum]) -> list[str]:
    return [item.value for item in enum_cls]


class AuthProvider(StrEnum):
    GOOGLE = "google"
    KAKAO = "kakao"
    GUEST = "guest"


class OnboardingStatus(StrEnum):
    PENDING = "pending"
    TERMS_AGREED = "terms_agreed"
    PROFILE_REQUIRED = "profile_required"
    COMPLETED = "completed"


class FontSize(StrEnum):
    SMALL = "small"
    MEDIUM = "medium"
    LARGE = "large"


class SoundSize(StrEnum):
    SMALL = "small"
    MEDIUM = "medium"
    LARGE = "large"


class TermsType(StrEnum):
    SERVICE = "service"
    PRIVACY = "privacy"
    SENSITIVE_HEALTH = "sensitive_health"
    MARKETING = "marketing"


class HealthCheckStatus(StrEnum):
    STARTED = "started"
    COMPLETED = "completed"
    SKIPPED = "skipped"


class InputMethod(StrEnum):
    FORM = "form"
    VOICE = "voice"
    SERVICE_LOG = "service_log"
    SENSOR = "sensor"
    MANUAL = "manual"


class Sex(StrEnum):
    MALE = "male"
    FEMALE = "female"


class ActivityInputSource(StrEnum):
    SELF_REPORT = "self_report"
    SERVICE_LOG = "service_log"


class KidneyStatus(StrEnum):
    NONE = "none"
    KIDNEY_DISEASE = "kidney_disease"
    DIALYSIS = "dialysis"
    UNKNOWN = "unknown"


class ProteinRestrictionStatus(StrEnum):
    NONE = "none"
    RESTRICTED = "restricted"
    UNKNOWN = "unknown"


class AssessmentType(StrEnum):
    INITIAL = "initial"
    REASSESSMENT = "reassessment"


class ActivityLevel(StrEnum):
    EASY = "easy"
    NORMAL = "normal"
    HARD = "hard"


# 현재 난이도 상태의 사유 (user_activity_profiles.level_reason). 명세 v7.2 운동 난이도 조회.
class LevelReason(StrEnum):
    INITIAL_TEST = "initial_test"
    RULE = "rule"
    LLM_RECOMMENDATION = "llm_recommendation"
    USER_SELECTED = "user_selected"


# 난이도 '변경 요청'의 사유 (운동 난이도 변경 요청 본문 reason_type). level_reason과 분리한다.
# 요청 이력은 후속 activity_level_change_logs 테이블에 남긴다(현재는 검증만, 미저장).
class ReasonType(StrEnum):
    RULE = "rule"
    LLM_RECOMMENDATION = "llm_recommendation"
    USER_REQUEST = "user_request"


class ModelVariant(StrEnum):
    MINIMAL = "minimal"
    WITH_WAIST = "with_waist"
    RULE_BASED_SCAFFOLD = "rule_based_scaffold"


class RiskLevel(StrEnum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class MissionType(StrEnum):
    MEAL = "meal"
    EXERCISE = "exercise"
    WALKING = "walking"
    GAME = "game"


class ExerciseCategory(StrEnum):
    WARM_UP = "warm_up"
    SEATED = "seated"
    STANDING = "standing"
    COOL_DOWN = "cool_down"


class ActivityType(StrEnum):
    WALKING = "walking"
    CHAIR_STAND = "chair_stand"
    SEATED_EXERCISE = "seated_exercise"
    STANDING_EXERCISE = "standing_exercise"
    STRETCHING = "stretching"


class TargetUnit(StrEnum):
    REPS = "reps"
    MINUTES = "minutes"
    STEPS = "steps"
    COUNT = "count"
    KM = "km"
    SETS = "sets"


class Intensity(StrEnum):
    LOW = "low"
    MODERATE = "moderate"
    HIGH = "high"


class MissionStatus(StrEnum):
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    SKIPPED = "skipped"


class PerceivedDifficulty(StrEnum):
    EASY = "easy"
    JUST_RIGHT = "just_right"
    HARD = "hard"


class SensorType(StrEnum):
    ACCELEROMETER = "accelerometer"
    GYROSCOPE = "gyroscope"
    STEP_COUNTER = "step_counter"


class RecognitionStatus(StrEnum):
    SUCCESS = "success"
    LOW_CONFIDENCE = "low_confidence"
    FAILED = "failed"
    MANUAL_OVERRIDE = "manual_override"


class ActivitySource(StrEnum):
    SENSOR = "sensor"
    MANUAL = "manual"


class SyncStatus(StrEnum):
    SYNCED = "synced"
    PENDING = "pending"
    RECOVERED = "recovered"


class GameType(StrEnum):
    CARD_MATCH = "card_match"


class DailyResult(StrEnum):
    NONE = "none"
    SUCCESS = "success"
    GREAT_SUCCESS = "great_success"
