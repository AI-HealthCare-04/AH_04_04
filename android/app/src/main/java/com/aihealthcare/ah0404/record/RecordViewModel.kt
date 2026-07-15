package com.aihealthcare.ah0404.record

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch

/**
 * `_13 나의 기록` 상태 + 백엔드 배선.
 *
 *  두 소스를 함께 읽어 화면 상태를 만든다(둘 중 하나만 실패해도 화면은 성립):
 *   - care_stage 추이(#62)  → 순화 등급 타임라인. 비노출 계약: 점수/등급 없음.
 *   - mission-logs 목록      → 활동 요약(완료 미션 수 + 누적 적립 포인트).
 *
 *  실패 시 error 를 설정하고 사용자가 다시 시도(재조회)할 수 있게 한다.
 */
class RecordViewModel(
    private val api: RecordApi = retrofit.create(RecordApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var history by mutableStateOf<List<RiskHistoryItem>>(emptyList()); private set
    var completedMissions by mutableStateOf(0); private set
    var totalPoints by mutableStateOf(0); private set
    var loaded by mutableStateOf(false); private set

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching {
                history = api.getRiskHistory().predictions
                val logs = api.getMissionLogs().logs
                completedMissions = logs.count { it.success }
                totalPoints = logs.sumOf { it.earnedPoints }
            }.onFailure {
                Log.w(TAG, "나의 기록 불러오기 실패: ${it.message}")
                error = "기록을 불러오지 못했어요. 네트워크를 확인하고 다시 시도해 주세요."
            }
            loaded = true
            loading = false
        }
    }

    fun dismissError() { error = null }

    companion object {
        const val TAG = "Record"
    }
}
