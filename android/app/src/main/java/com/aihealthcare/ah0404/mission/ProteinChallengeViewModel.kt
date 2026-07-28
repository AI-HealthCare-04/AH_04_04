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
    result.savedCount == 0 ->
        "오늘은 단백질을 안 드신 것으로 저장했어요.\n${PROTEIN_DAILY_GOAL}가지 이상 드시면 포인트를 받을 수 있어요."
    else ->
        // 계약(#227)상 0종만 미완료라 정상 경로에서는 도달하지 않는 방어 분기(서버 판정 변동 대비).
        "오늘 드신 단백질을 저장했어요.\n${PROTEIN_DAILY_GOAL}가지 이상 드시면 포인트를 받을 수 있어요."
}

/** 저장 버튼 문구 — 0개 선택도 '안 먹었어요' 기록으로 저장 가능함을 안내(#224 계약, 리뷰 #225 P2). */
internal fun proteinSaveButtonLabel(count: Int): String = when {
    count == 0 -> "안 먹었어요로 저장하기"
    count >= PROTEIN_DAILY_GOAL -> "목표 달성! 저장하기"
    else -> "저장하기" // 목표(#227: 1종)에서는 도달하지 않음 — 목표를 되올릴 때를 위한 일반형
}

class ProteinChallengeViewModel : ViewModel() {
    private val api = retrofit.create(MissionApi::class.java)

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

    /** 화면 진입 시 호출 — 이전 방문/계정/날짜의 달성 추적을 버린다(리뷰 #225 2차). */
    fun onScreenEntered() {
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
