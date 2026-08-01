package com.aihealthcare.ah0404.mission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.MealDetail
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 단백질 식사 저장 상태(#93 후속). */
sealed class ProteinSaveState {
    object Idle : ProteinSaveState()
    object Saving : ProteinSaveState()

    /**
     * 저장 성공. 카운트/적립 여부는 서버 판정을 그대로 따른다(1종 이상 카운트, 0종만 미완료 — #227).
     * @param newlyCounted 이번 저장으로 **처음** 목표 달성이 됐는가. 서버의 earned_points 는 '오늘 반영된
     *   포인트'라 재저장에도 같은 값이 오므로(리뷰 #225 P1), 신규 지급 문구는 이 플래그로만 구분한다.
     * @param savedCount 이번에 저장한 카테고리 개수(0 = '오늘 안 먹었어요' 기록, #224 계약).
     */
    data class Saved(
        val countedForDaily: Boolean,
        val earnedPoints: Int,
        val newlyCounted: Boolean,
        val savedCount: Int,
        /** 이번 저장으로 회수된 포인트(#343 문제 3, 리뷰 #350). 달성 상태에서 미달로 내려간 저장에만 > 0. */
        val revokedPoints: Int = 0,
    ) : ProteinSaveState()

    data class Error(val message: String) : ProteinSaveState()
}

/**
 * 화면 진입 시점의 오늘 기록(today_log)으로 '이미 목표 달성 상태였는가'를 판정한다(리뷰 #225 P1).
 * 서버 판정 규칙(서로 다른 카테고리 [PROTEIN_DAILY_GOAL]종 이상)과 같은 기준을 표시용으로만 쓴다.
 * 순수 함수 — JVM 단위테스트로 고정.
 */
internal fun wasCountedFromTodayLog(eaten: List<String>?): Boolean =
    (eaten.orEmpty().distinct().count { id -> PROTEIN_CATEGORIES.any { it.id == id } }) >= PROTEIN_DAILY_GOAL

/**
 * 저장 완료 오버레이 본문(리뷰 #225 P1·P2). 재저장에 '받았어요'를 반복하지 않고,
 * 빈 기록(0개)은 '안 먹었어요' 저장임을 분명히 안내한다. 순수 함수 — JVM 단위테스트로 고정.
 */
internal fun proteinResultMessage(result: ProteinSaveState.Saved): String = when {
    result.newlyCounted ->
        "오늘 단백질을 잘 챙기셨어요.\n${result.earnedPoints}포인트를 받았어요!"
    result.countedForDaily ->
        "오늘 단백질을 잘 챙기셨어요.\n오늘 ${result.earnedPoints}포인트가 이미 반영되어 있어요."
    // 회수 발생(#343 문제 3, 리뷰 #350): 경고 다이얼로그에서 예고한 회수가 실제로 됐음을 결과에서도 확인시킨다.
    result.savedCount == 0 && result.revokedPoints > 0 ->
        "오늘은 단백질을 안 드신 것으로 저장했어요.\n받았던 ${result.revokedPoints}포인트는 취소됐어요."
    result.revokedPoints > 0 ->
        "오늘 드신 단백질을 저장했어요.\n목표 미달로 받았던 ${result.revokedPoints}포인트는 취소됐어요."
    result.savedCount == 0 ->
        "오늘은 단백질을 안 드신 것으로 저장했어요.\n${PROTEIN_DAILY_GOAL}가지 이상 드시면 포인트를 받을 수 있어요."
    else ->
        // 계약(#227)상 0종만 미완료라 정상 경로에서는 도달하지 않는 방어 분기(서버 판정 변동 대비).
        "오늘 드신 단백질을 저장했어요.\n${PROTEIN_DAILY_GOAL}가지 이상 드시면 포인트를 받을 수 있어요."
}

/**
 * 저장 중(Saving)에는 화면 이탈을 막는다(리뷰 #225 3차) — 이탈을 허용하면 응답 도착 시점에
 * 화면이 없어 목록 재조회(onSaved)가 누락되고 stale today_log 문제가 재발한다. 순수 함수.
 */
internal fun proteinBackAllowed(state: ProteinSaveState): Boolean = state !is ProteinSaveState.Saving

/** 저장 버튼 문구 — 0개 선택도 '안 먹었어요' 기록으로 저장 가능함을 안내(#224 계약, 리뷰 #225 P2). */
internal fun proteinSaveButtonLabel(count: Int): String = when {
    count == 0 -> "안 먹었어요로 저장하기"
    count >= PROTEIN_DAILY_GOAL -> "목표 달성! 저장하기"
    else -> "저장하기" // 목표(#227: 1종)에서는 도달하지 않음 — 목표를 되올릴 때를 위한 일반형
}

/**
 * 오늘 기록 상태 안내(#343 문제 1): '안 먹었어요 저장'과 '아직 기록 안 함'이 화면상 동일(선택 0개)해
 * 구분이 안 되던 문제 — 오늘 기록(today_log)이 있으면 그 내용·시각을 명시한다. 기록 없으면 null(미표시).
 */
internal fun proteinTodayStatusLine(eatenCount: Int?, loggedAt: String?): String? {
    if (eatenCount == null) return null
    val time = proteinLoggedAtTime(loggedAt)
    val base = if (eatenCount == 0) "오늘은 '안 먹었어요'로 기록되어 있어요" else "오늘 ${eatenCount}가지를 기록하셨어요"
    val stamp = if (time != null) " ($time 기록)" else ""
    return "$base$stamp · 다시 골라 저장하면 수정돼요"
}

/** ISO 시각("2026-07-31T09:20:…")에서 표시용 HH:mm 만 뽑는다. 형식이 다르면 null(시각 생략). */
internal fun proteinLoggedAtTime(loggedAt: String?): String? {
    val hhmm = loggedAt?.substringAfter('T', missingDelimiterValue = "")?.take(5) ?: return null
    return if (Regex("\\d{2}:\\d{2}").matches(hhmm)) hhmm else null
}

/**
 * 포인트 회수 경고 필요 판정(#343 문제 3): 오늘 이미 목표 달성으로 저장(포인트 적립)했는데
 * 목표 미달(0종 포함)로 다시 저장하려는 경우 — 서버 upsert 가 counted 를 되돌려 받은 포인트가
 * 회수되므로, 조용히 저장하지 않고 확인을 받는다. 순수 함수(테스트 대상).
 */
internal fun proteinDowngradeNeedsConfirm(previousEatenCount: Int?, newCount: Int): Boolean =
    (previousEatenCount ?: 0) >= PROTEIN_DAILY_GOAL && newCount < PROTEIN_DAILY_GOAL

class ProteinChallengeViewModel(
    // 실경로는 공용 retrofit. JVM 테스트는 fake 를 주입해 저장 상태 수명(진입 리셋 등)을 검증한다.
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
) : ViewModel() {

    private val _saveState = MutableStateFlow<ProteinSaveState>(ProteinSaveState.Idle)
    val saveState: StateFlow<ProteinSaveState> = _saveState

    /**
     * '이미 목표 달성 상태였는가' 추적(리뷰 #225 P1). 최초값은 진입 시점 today_log 로 추정하고,
     * 같은 화면에서 저장을 거듭하면 직전 서버 판정으로 갱신한다 — 첫 달성에만 '받았어요'를 띄우기 위함.
     *
     * ⚠️ 이 VM 은 Activity 범위라 화면을 나가도 살아남는다. 추적값을 **화면 진입마다 리셋**해야
     *   재진입·사용자/날짜 전환에서 이전 방문의 판정이 문구를 오염시키지 않는다(리뷰 #225 2차).
     *   리셋 후 첫 저장은 fresh today_log(저장 성공 시 목록 재조회로 갱신됨)로 다시 추정한다.
     */
    private var countedBefore: Boolean? = null

    /**
     * 화면 진입 시 호출 — 이전 방문의 상태를 **전부** 버린다(리뷰 #225 2차·3차).
     * Activity 범위 VM 이라 saveState 를 남겨두면, 결과/오류 오버레이에서 시스템 뒤로가기로 나갔다
     * 재진입할 때 이전 Saved/Error 가 즉시 재노출되고 Saved 면 onSaved 도 재호출된다.
     */
    fun onScreenEntered() {
        _saveState.value = ProteinSaveState.Idle
        countedBefore = null
    }

    /**
     * 오늘 고른 단백질 카테고리를 저장한다(POST /mission-logs, status=completed).
     *
     * 최종 성공/카운트/포인트 판정은 **서버**가 한다(1종 이상 = 카운트, #227). 앱은 success 를 보내지 않고
     * 카테고리 목록만 넘긴다 — 서버가 개수로 판정하므로 클라이언트가 규칙을 중복 구현하지 않는다.
     * 같은 날 재저장은 서버에서 upsert 로 오늘 기록을 갱신한다(이중 적립 없음).
     */
    fun save(mission: Mission, selectedIds: List<String>) {
        viewModelScope.launch {
            _saveState.value = ProteinSaveState.Saving
            try {
                // 첫 저장 전이면 진입 시점 today_log 로 '이미 달성 상태였는가'를 추정한다(리뷰 #225 P1).
                val wasCounted = countedBefore ?: wasCountedFromTodayLog(mission.todayLog?.eaten)
                val resp = api.createMissionLog(
                    MissionLogCreateRequest(
                        missionTemplateId = mission.missionTemplateId,
                        missionType = "meal",
                        status = "completed",
                        mealDetail = MealDetail(
                            proteinFoods = selectedIds,
                            proteinMealCount = selectedIds.size,
                        ),
                    )
                )
                countedBefore = resp.countedForDaily
                _saveState.value = ProteinSaveState.Saved(
                    countedForDaily = resp.countedForDaily,
                    earnedPoints = resp.earnedPoints,
                    newlyCounted = resp.countedForDaily && !wasCounted,
                    savedCount = selectedIds.size,
                    // 달성 → 미달 전환(#343 문제 3): 서버 upsert 가 counted 를 되돌렸으면 적립됐던
                    //   템플릿 포인트가 회수된 것 — 결과 오버레이에 사실대로 알린다(리뷰 #350).
                    revokedPoints = if (wasCounted && !resp.countedForDaily) mission.rewardPoints else 0,
                )
            } catch (e: Exception) {
                _saveState.value = ProteinSaveState.Error(e.message ?: "저장에 실패했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    fun reset() {
        _saveState.value = ProteinSaveState.Idle
        countedBefore = null // 다음 진입은 fresh today_log 로 다시 추정(오염 방지, 리뷰 #225 2차)
    }
}
