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

    /** 저장 성공. 카운트/적립 여부는 서버 판정을 그대로 따른다(3종 이상만 카운트). */
    data class Saved(val countedForDaily: Boolean, val earnedPoints: Int) : ProteinSaveState()
    data class Error(val message: String) : ProteinSaveState()
}

class ProteinChallengeViewModel : ViewModel() {
    private val api = retrofit.create(MissionApi::class.java)

    private val _saveState = MutableStateFlow<ProteinSaveState>(ProteinSaveState.Idle)
    val saveState: StateFlow<ProteinSaveState> = _saveState

    /**
     * 오늘 고른 단백질 카테고리를 저장한다(POST /mission-logs, status=completed).
     *
     * 최종 성공/카운트/포인트 판정은 **서버**가 한다(3종 이상 = 카운트). 앱은 success 를 보내지 않고
     * 카테고리 목록만 넘긴다 — 서버가 개수로 판정하므로 클라이언트가 규칙을 중복 구현하지 않는다.
     * 같은 날 재저장은 서버에서 upsert 로 오늘 기록을 갱신한다(이중 적립 없음).
     */
    fun save(mission: Mission, selectedIds: List<String>) {
        viewModelScope.launch {
            _saveState.value = ProteinSaveState.Saving
            try {
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
                _saveState.value = ProteinSaveState.Saved(
                    countedForDaily = resp.countedForDaily,
                    earnedPoints = resp.earnedPoints,
                )
            } catch (e: Exception) {
                _saveState.value = ProteinSaveState.Error(e.message ?: "저장에 실패했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    fun reset() {
        _saveState.value = ProteinSaveState.Idle
    }
}
