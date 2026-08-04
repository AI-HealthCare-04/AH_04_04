package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.Mission

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

/**
 * 미션 카드 하단 CTA 문구 — 목적지가 실제 수행/기록 화면인 유형은 그에 맞는 행동을 안내한다(리뷰 #225).
 * '준비 중'은 **수행 화면이 정말 없는 유형(COMING_SOON)에만** 쓴다 — 운동 영상(#219)·미니게임(#220)·
 * 식사 기록처럼 실화면이 있는 유형에 '준비 중'을 표시하면 사용자가 기능이 없는 줄 안다(리뷰 #225 2차).
 * 순수 함수 — 라우팅 규칙과 함께 JVM 단위테스트로 고정한다.
 */
internal fun missionCtaLabel(missionType: String): String =
    when (missionDestination(missionType)) {
        MissionDestination.WALKING -> "눌러서 측정 시작 →"
        MissionDestination.PROTEIN_MEAL -> "눌러서 기록하기 →"
        MissionDestination.EXERCISE_VIDEOS -> "눌러서 운동 영상 보기 →"
        MissionDestination.MINI_GAME -> "눌러서 게임 하기 →"
        MissionDestination.COMING_SOON -> "준비 중 · 눌러서 보기 →"
    }

/** CTA 를 강조색으로 그릴지 — 실제 수행/기록 화면으로 바로 이어지는 유형만 강조한다. */
internal fun missionCtaHighlighted(missionType: String): Boolean =
    missionDestination(missionType) != MissionDestination.COMING_SOON

/**
 * 단백질(고단백 식사) 미션 숨김 사유 문구(#304 요청 4). 목록에 meal 유형이 없을 때 최신 프로필의
 * 신장·단백질 제한 상태로 '왜 안 보이는지 + 내 정보에서 바꿀 수 있음'을 안내한다(막힌 이유를 모른 채
 * 미션이 사라진 것처럼 보이는 문제 해소). 게이트 판정 자체는 서버 소관(kidney==none AND protein==none
 * 일 때만 노출) — 여기서는 안내 문구만 만든다. 사유를 특정할 수 없으면 null(카드 미표시).
 * 값: kidney = none|kidney_disease|dialysis|unknown, protein = none|restricted|unknown.
 */
internal fun proteinHiddenReason(kidneyStatus: String, proteinStatus: String): String? = when {
    kidneyStatus == "kidney_disease" || kidneyStatus == "dialysis" ->
        "신장 건강을 위해 고단백 식사 미션을 잠시 쉬고 있어요. 상태가 달라졌다면 내 정보에서 알려주세요."
    proteinStatus == "restricted" ->
        "단백질 섭취 제한이 있어 고단백 식사 미션을 잠시 쉬고 있어요. 제한이 풀렸다면 내 정보에서 알려주세요."
    kidneyStatus == "unknown" || proteinStatus == "unknown" ->
        "신장 상태와 단백질 제한 여부를 내 정보에서 알려주시면 고단백 식사 미션을 열어드려요."
    else -> null // 둘 다 none 인데 안 보이면 다른(서버) 사정 — 추측 안내를 하지 않는다.
}

/**
 * 신장·단백질을 '잘 모르겠어요'로 고를 때 **그 자리에서** 보여줄 안내.
 *
 * 게이트는 kidney==none AND protein==none 일 때만 고단백 식사 미션을 연다. 즉 '잘 모르겠어요'도
 * 미션을 막는다. '신장질환 있음'·'제한 중'은 막히는 이유가 사용자에게 자명하지만, '잘 모르겠어요'는
 * 중립적인 선택으로 읽혀 미션이 사라진 걸 나중에야 알게 된다 — [proteinHiddenReason] 은 이미 사라진
 * 뒤 미션 화면에서야 이유를 알려주므로, 고르는 시점에 미리 알려준다.
 *
 * 온보딩 3단계와 설정 → 내 정보 두 곳에서 함께 쓴다(같은 선택을 하는 두 자리).
 */
internal const val PROTEIN_GATE_UNKNOWN_NOTICE =
    "'잘 모르겠어요'를 고르시면 안전을 위해 고단백 식사 미션이 잠시 숨겨져요. " +
        "나중에 알게 되시면 설정 → 내 정보에서 언제든 바꿀 수 있어요."


/**
 * 1회성 미션(식사·게임) 카드의 '오늘 했음' 표시 문구(#346). 시간 목표가 없어 진행바 대신 배지로 표현한다.
 *  - 식사: today_log 기준 — 1종 이상이면 달성 배지, 0종(안 먹었어요)도 기록임을 보여준다(#343 가시성).
 *  - 게임: today_done(#346 백엔드) 기준. 구버전 서버(null)면 표시하지 않는다(호환).
 *  - 걷기·운동은 기존 today_progress 진행바가 담당(null 반환).
 */
internal fun missionTodayBadge(mission: Mission): String? = when (missionDestination(mission.missionType)) {
    MissionDestination.PROTEIN_MEAL -> mission.todayLog?.let { log ->
        if (log.eaten.isEmpty()) "오늘은 안 드신 것으로 기록했어요" else "오늘 단백질 챙겼어요 🎉 · ${log.eaten.size}가지"
    }
    MissionDestination.MINI_GAME -> if (mission.todayDone == true) "오늘 게임 했어요 🎉" else null
    else -> null
}
