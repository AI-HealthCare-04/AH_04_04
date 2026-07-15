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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * `_13 나의 기록` 상태 + 백엔드 배선.
 *
 *  두 소스를 **서로 독립적으로** 조회한다(리뷰 #68 지영 지적 2 반영):
 *   - care_stage 추이(#62)  → 순화 등급 타임라인. 비노출 계약: 점수/등급 없음.
 *   - mission-logs 목록      → 활동 요약(완료 미션 수 + 누적 적립 포인트).
 *  한쪽 호출이 실패해도 다른 쪽은 조회·표시되며, 실패한 섹션은 각자의 오류 상태로 표시한다
 *  (성공 섹션엔 낡은 값이 섞이지 않는다).
 *
 *  화면은 진입할 때마다 load() 를 호출한다(리뷰 #68 지적 1 — 재진입 시 최신 기록 재조회).
 */
class RecordViewModel(
    private val api: RecordApi = retrofit.create(RecordApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set // 최초 조회 완료 여부(빈 상태 구분용)

    var history by mutableStateOf<List<RiskHistoryItem>>(emptyList()); private set
    var historyError by mutableStateOf(false); private set

    var completedMissions by mutableStateOf(0); private set
    var totalPoints by mutableStateOf(0); private set
    var activityError by mutableStateOf(false); private set

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /**
     * 두 소스 동시 조회(각각 독립 성공/실패). 재조회 진입점이자 테스트 진입점.
     *  - 위험도 이력 성공 → history 갱신 / 실패 → historyError
     *  - 미션 로그 성공 → 활동 요약 갱신 / 실패 → activityError
     */
    suspend fun refresh() {
        loading = true
        historyError = false
        activityError = false
        coroutineScope {
            val historyCall = async { runCatching { api.getRiskHistory().predictions } }
            val logsCall = async { runCatching { api.getMissionLogs().logs } }

            historyCall.await()
                .onSuccess { history = it }
                .onFailure { historyError = true; Log.w(TAG, "위험도 이력 조회 실패: ${it.message}") }

            logsCall.await()
                .onSuccess { logs ->
                    completedMissions = logs.count { it.success }
                    totalPoints = logs.sumOf { it.earnedPoints }
                }
                .onFailure { activityError = true; Log.w(TAG, "미션 로그 조회 실패: ${it.message}") }
        }
        loaded = true
        loading = false
    }

    companion object {
        const val TAG = "Record"
    }
}
