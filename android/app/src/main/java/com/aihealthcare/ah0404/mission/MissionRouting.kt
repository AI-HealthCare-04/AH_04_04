package com.aihealthcare.ah0404.mission

/**
 * 미션 유형 → 이동할 화면(라우팅만, #93 A-3).
 *
 * 이 화면들은 **이동만** 담당하고 실제 수행·기록 생성은 지지 않는다.
 *  - 걷기 기록은 #90 세션 화면 → #91 이 유일한 기록 지점이다(여기서 직접 POST 금지, 이중 경로 금지).
 *  - 게임은 미니게임 영상 화면으로 연결. 식사의 실제 수행 화면은 후속 이슈로 분리 → 지금은 '준비 중'.
 */
enum class MissionDestination {
    /** 걷기: #90 보행 세션 측정 화면. */
    WALKING,

    /**
     * 운동: 영상 따라 하기 화면(ExerciseVideosScreen). 홈의 '영상 따라 운동하기'와 같은 목적지다.
     *  스트리밍 영상(#72)은 서버 업로드 전이라 대부분 '준비중'이지만, 몸풀기는 앱 번들 루틴이라
     *  오프라인에서도 재생된다. 미션 탭만 '준비 중'으로 막아 홈과 동작이 갈리던 문제를 없앤다(#162).
     */
    EXERCISE_VIDEOS,

    /** 게임: 미니게임 영상 화면(MiniGameScreen). 서버 /videos 로 스트리밍하는 짧은 재미 영상. */
    MINI_GAME,

    /**
     * 식사: 단백질 식사 기록 화면(ProteinChallengeScreen). 신장질환자를 제외한 사용자가
     * 오늘 먹은 단백질 카테고리를 골라 저장한다. 걷기와 달리 이 화면이 유일한 기록 지점이다
     * (즉시완료 미션이라 별도 세션 흐름이 없다).
     */
    PROTEIN_MEAL,

    /** 아직 수행 화면이 없는 유형: '준비 중' 안내 화면. */
    COMING_SOON,
}

/**
 * 미션 유형 문자열("walking" | "exercise" | "meal" | "game" | 미지의 값)을 목적지로 매핑.
 * 순수 함수 — 라우팅 규칙을 JVM 단위테스트로 검증하고, 걷기 외 유형이 걷기 경로로 새지 않음을 보장한다.
 */
internal fun missionDestination(missionType: String): MissionDestination =
    when (missionType) {
        "walking" -> MissionDestination.WALKING
        "exercise" -> MissionDestination.EXERCISE_VIDEOS
        "game" -> MissionDestination.MINI_GAME
        "meal" -> MissionDestination.PROTEIN_MEAL
        else -> MissionDestination.COMING_SOON
    }
