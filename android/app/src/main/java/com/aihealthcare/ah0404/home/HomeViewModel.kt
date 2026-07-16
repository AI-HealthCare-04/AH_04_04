package com.aihealthcare.ah0404.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.HomeApi
import com.aihealthcare.ah0404.network.HomeResponse
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 홈(_3) 상태 + 백엔드 배선(GET /home). mock 제거 → 실제 서버 응답을 HomeUi 로 매핑.
 *
 *  진입마다 재조회 + generation 가드(최신 요청만 commit). 실패 시 error(재시도), 성공 전엔 ui=null.
 *  ⚠️ 비노출 계약(#57): care_stage/display_message 만 사용(위험도 점수/등급 없음).
 */
class HomeViewModel(
    private val api: HomeApi = retrofit.create(HomeApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var ui by mutableStateOf<HomeUi?>(null); private set

    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val gen = ++generation
        loading = true
        error = false
        val result = try {
            Result.success(api.getHome())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != generation) return
        result
            .onSuccess { ui = it.toUi() }
            .onFailure { error = true; Log.w(TAG, "홈 조회 실패: ${it.message}") }
        loading = false
    }

    companion object {
        const val TAG = "Home"
    }
}

/** GET /home 응답 → 화면용 HomeUi. 비노출 계약: care_stage/display_message 만 노출. */
fun HomeResponse.toUi(): HomeUi = HomeUi(
    nickname = user.nickname,
    points = pointBalance.currentPoints,
    activityLevel = activityProfile.currentLevel,
    careStage = latestPrediction?.careStage,
    predictionMessage = latestPrediction?.displayMessage,
    disclaimer = null, // 홈 latest_prediction 엔 disclaimer 없음 → 기본 고지 사용
    completedToday = todaySummary.countedMissionCount,
    availableMeal = availableMissionSummary.meal,
    availableExercise = availableMissionSummary.exercise,
    availableWalking = availableMissionSummary.walking,
    availableGame = availableMissionSummary.game,
    todayWalkingMin = todayWalking.dailyTotalMin,
    todayWalkingSteps = todayWalking.dailyTotalSteps,
)
