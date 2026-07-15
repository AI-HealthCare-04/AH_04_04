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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * `_13 나의 기록` 상태 + 백엔드 배선.
 *
 *  두 소스를 **서로 독립적으로** 조회한다(리뷰 #68 지영 지적 2):
 *   - care_stage 추이(#62)  → 순화 등급 타임라인. 비노출 계약: 점수/등급 없음.
 *   - mission-logs 목록      → 활동 요약(완료 미션 수 + 누적 적립 포인트).
 *  한쪽 호출이 실패해도 다른 쪽은 조회·표시되며, 실패한 섹션은 각자의 오류 상태로 표시한다.
 *
 *  화면은 진입할 때마다 load() 를 호출한다(지적 1 — 재진입 시 최신 기록 재조회).
 *
 *  ⚠️ 겹친 재조회 경쟁 방지(지적 3): 각 refresh 는 generation 토큰을 받고, **가장 최근 refresh 만**
 *     상태를 commit 한다. 느린 이전 조회가 늦게 끝나도 최신 값을 덮어쓰지 않는다.
 *     취소 예외(CancellationException)는 삼키지 않고 그대로 전파한다(구조적 동시성 보존).
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

    // 겹친 refresh 중 최신 것만 상태를 commit 하도록 식별하는 세대 토큰.
    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /**
     * 두 소스 동시 조회(각각 독립 성공/실패). 재조회/테스트 진입점.
     *  - 최신 세대(generation)만 상태를 반영 → 겹친 조회에서 오래된 응답이 최신 값을 덮지 않음.
     */
    suspend fun refresh() {
        val gen = ++generation
        loading = true
        historyError = false
        activityError = false
        coroutineScope {
            val historyCall = async { safeCall { api.getRiskHistory().predictions } }
            val logsCall = async { safeCall { api.getMissionLogs().logs } }
            val historyResult = historyCall.await()
            val logsResult = logsCall.await()

            // 이 refresh 이후 더 최신 refresh 가 시작됐다면, 낡은 결과는 버린다(commit 안 함).
            if (gen != generation) return@coroutineScope

            historyResult
                .onSuccess { history = it }
                .onFailure { historyError = true; Log.w(TAG, "위험도 이력 조회 실패: ${it.message}") }
            logsResult
                .onSuccess { logs ->
                    completedMissions = logs.count { it.success }
                    totalPoints = logs.sumOf { it.earnedPoints }
                }
                .onFailure { activityError = true; Log.w(TAG, "미션 로그 조회 실패: ${it.message}") }
            loaded = true
        }
        if (gen == generation) loading = false
    }

    /** 취소 예외는 그대로 전파(구조적 동시성 보존), 실제 오류만 Result.failure 로 변환. */
    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        const val TAG = "Record"
    }
}
