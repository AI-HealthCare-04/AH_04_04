package com.aihealthcare.ah0404.record

/**
 * 점수 재평가(`POST /risk-predictions/reassess`) 진행 상태(#388).
 *
 * **두 곳이 같은 엔드포인트를 부른다** — 내 정보 저장 흐름(`HealthInfoViewModel`)과 기록 탭의
 * '점수 다시 계산하기'. 하루 1회 제한(#396)도 양쪽에 똑같이 걸리므로 상태와 문구를 한곳에 둔다.
 * 따로 두면 한쪽만 고쳐져 같은 상황에 다른 말을 하게 된다.
 */
enum class ScoreRefreshState {
    IN_PROGRESS,

    /** 새로 계산돼 점수가 갱신됐다. */
    APPLIED,

    /**
     * 오늘 이미 계산해 **기존 예측을 그대로 받았다**(#396 하루 1회). 점수는 있지만 새 값이 아니다.
     * 이걸 APPLIED 로 뭉뚱그리면 "바로 반영됐어요"라고 해놓고 실제로는 어제 점수를 보여주게 된다.
     */
    ALREADY_TODAY,

    /** 422(연령 미지원 등)·2xx 점수 미제공 — 재시도해도 같으므로 재시도 버튼을 주지 않는다. */
    NOT_ELIGIBLE,

    /** 네트워크·5xx — 재시도 의미가 있다. */
    FAILED,
}

/**
 * 서버 응답을 상태로 옮긴다. **순수 함수** — 하루 1회 제한과 점수 미제공을 구분하는 규칙을 테스트로 고정한다.
 *
 * @param recalculated `false` = 오늘 이미 계산해 기존 예측을 돌려준 것
 */
fun scoreRefreshResult(recalculated: Boolean, muscleScore: Int?): ScoreRefreshState = when {
    // 점수 자체가 없으면 다시 계산했든 아니든 제공 대상이 아니다. 이 판정이 먼저다.
    muscleScore == null -> ScoreRefreshState.NOT_ELIGIBLE
    recalculated -> ScoreRefreshState.APPLIED
    else -> ScoreRefreshState.ALREADY_TODAY
}

/**
 * 지금 재계산을 **요청할 수 있는 상태인가**(리뷰 P2). 버튼 활성 조건이자 정책 그 자체다.
 *
 * 아직 안 눌렀거나 실패한 경우에만 눌러서 결과가 달라진다. 나머지는 다시 눌러도 서버가 같은 답을
 * 준다 — `APPLIED` 직후도 마찬가지다(다음 호출은 `recalculated=false`). 눌리는 버튼을 두면
 * "눌러도 안 바뀐다"는 경험만 반복시킨다.
 */
fun canRequestScoreRefresh(
    state: ScoreRefreshState?,
    nextAvailableAtMillis: Long? = null,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean = when (state) {
    null -> true
    ScoreRefreshState.FAILED -> true
    ScoreRefreshState.IN_PROGRESS, ScoreRefreshState.NOT_ELIGIBLE -> false
    // 하루 1회 제한은 **시각이 지나면 풀린다**(리뷰). 상태만 보고 잠그면 앱을 켜둔 채 자정을
    //   넘겨도 다음 실행 전까지 기능이 잠긴다 — 서버는 이미 허용하는데 화면만 막는 꼴이다.
    //   시각을 모르면(구버전 서버·파싱 실패) 잠그지 않는다: 다시 눌러도 서버가 같은 답을 줄 뿐이고,
    //   기능이 영영 잠기는 쪽이 훨씬 나쁘다.
    ScoreRefreshState.APPLIED, ScoreRefreshState.ALREADY_TODAY ->
        nextAvailableAtMillis == null || nowMillis >= nextAvailableAtMillis
}

/**
 * 서버가 준 다음 재평가 가능 시각(`2026-08-05T00:00:00+09:00`)을 epoch millis 로.
 *
 * `java.time` 은 minSdk 24 에서 desugaring 없이 못 쓰므로 [java.text.SimpleDateFormat] 을 쓴다.
 * 오프셋 표기가 기기·서버 버전에 따라 갈릴 수 있어 몇 가지를 순서대로 시도하고, 전부 실패하면
 * null 을 돌려준다 — 위 판정이 그 경우 **잠그지 않는 쪽**으로 처리한다.
 */
fun parseNextAvailableAt(iso: String?): Long? {
    val text = iso?.trim().orEmpty()
    if (text.isEmpty()) return null
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss")
    patterns.forEach { pattern ->
        val parsed = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(text)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

/**
 * 기록 탭 '점수 다시 계산하기' 아래에 붙는 상태 문구. null = 표시할 말 없음(아직 안 눌렀다).
 *
 * `ALREADY_TODAY` 에서는 **왜 안 바뀌었는지**를 말해야 한다 — 눌렀는데 숫자가 그대로면
 * 고장으로 읽힌다. 하루 1회라는 사실과 언제 다시 되는지를 함께 준다.
 */
fun scoreRefreshStatusText(state: ScoreRefreshState?): String? = when (state) {
    null -> null
    ScoreRefreshState.IN_PROGRESS -> "다시 계산하고 있어요…"
    ScoreRefreshState.APPLIED -> "새로 계산했어요. 위 점수에 반영됐어요."
    ScoreRefreshState.ALREADY_TODAY ->
        "오늘은 이미 계산했어요. 점수는 하루에 한 번 새로 계산해요 — 내일 다시 눌러 주세요."
    ScoreRefreshState.NOT_ELIGIBLE -> "지금은 근육 건강 점수 제공 대상이 아니에요."
    ScoreRefreshState.FAILED -> "다시 계산하지 못했어요. 잠시 후 다시 눌러 주세요."
}
