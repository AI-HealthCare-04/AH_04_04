package com.aihealthcare.ah0404.mission

/**
 * 미션 유형 → 이동할 화면(라우팅만, #93 A-3).
 *
 * 이 화면들은 **이동만** 담당하고 실제 수행·기록 생성은 지지 않는다.
 *  - 걷기 기록은 #90 세션 화면 → #91 이 유일한 기록 지점이다(여기서 직접 POST 금지, 이중 경로 금지).
 *  - 운동·식사·게임의 실제 수행 화면은 유형별 후속 이슈로 분리 → 지금은 '준비 중'으로 연결.
 */
enum class MissionDestination {
    /** 걷기: #90 보행 세션 측정 화면. */
    WALKING,

    /** 아직 수행 화면이 없는 유형(운동·식사·게임 등): '준비 중' 안내 화면. */
    COMING_SOON,
}

/**
 * 미션 유형 문자열("walking" | "exercise" | "meal" | "game" | 미지의 값)을 목적지로 매핑.
 * 순수 함수 — 라우팅 규칙을 JVM 단위테스트로 검증하고, 걷기 외 유형이 걷기 경로로 새지 않음을 보장한다.
 */
internal fun missionDestination(missionType: String): MissionDestination =
    when (missionType) {
        "walking" -> MissionDestination.WALKING
        else -> MissionDestination.COMING_SOON
    }
