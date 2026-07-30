package com.aihealthcare.ah0404.exercise

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.mission.ExerciseFlowUseCase
import com.aihealthcare.ah0404.network.ExerciseVideoApi
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운동 영상(4단계) 상태 + 백엔드 배선(GET /exercise-videos, #72).
 *
 *  진입 시 1회 조회 후 캐시, order 순 정렬. available=false 단계는 화면에서 "준비중" 표시.
 *  진입마다 재조회 + generation 가드(겹친 조회 시 최신만 commit).
 *
 *  운동 완료 배선(#234): 스트리밍(근력·서서) 시청 분·번들 루틴(몸풀기·마무리) 완주 분을 [submitExercise]
 *  로 서버에 올린다. 운동은 백엔드에서 '단일 미션'(영상 따라 운동하기)이라 4단계가 모두 같은 미션의
 *  하루 10분 목표에 합산된다(#168) → 화면은 미션 컨텍스트 없이 진입하므로(홈 버튼/미션 탭 공용),
 *  VM 이 GET /missions 로 그 단일 운동 템플릿 id 를 스스로 해석한다.
 */
class ExerciseVideosViewModel(
    private val api: ExerciseVideoApi = retrofit.create(ExerciseVideoApi::class.java),
    private val missionApi: MissionApi = retrofit.create(MissionApi::class.java),
    private val exerciseFlow: ExerciseFlowUseCase = ExerciseFlowUseCase(),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var videos by mutableStateOf<List<ExerciseVideoItem>>(emptyList()); private set

    private var generation = 0

    // 운동 단일 미션 템플릿 id(GET /missions 로 lazy 해석 후 캐시). 로그인 전/조회 실패면 null.
    private var exerciseTemplateId: Int? = null

    fun load() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val gen = ++generation
        loading = true
        error = false
        val result = try {
            Result.success(api.getExerciseVideos())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != generation) return
        result
            .onSuccess { videos = it.videos.sortedBy { v -> v.order } }
            .onFailure { error = true; Log.w(TAG, "운동 영상 조회 실패: ${it.message}") }
        loaded = true
        loading = false
    }

    /**
     * 운동 세션 한 건(스트리밍 시청 분 또는 루틴 완주 분)의 완료를 서버에 올린다(#234).
     *
     *  - **0분 이하**: 서버 ExerciseDetail.duration_min 은 gt=0(당일 누적 되돌리기 방어)이라, 즉시 이탈 등
     *    0분 세션은 아예 보내지 않고 조용히 무시한다 → 호출부(영상/루틴)는 이 가드를 믿고 콜백을 자유롭게 불러도 된다.
     *  - **안전 고지 미확인**: 운동은 서버가 확인을 요구하므로(true 아니면 400), 확인 게이트를 통과하지 않았으면
     *    보내지 않는다. [safetyNoticeConfirmed] 는 호출부(#254 화면의 확인 게이트)가 넘긴 **실제 확인 결과**다.
     *  - **템플릿 미해석**: 로그인 전이거나 GET /missions 가 실패해 운동 미션 id 를 못 얻으면 보낼 대상이 없어 생략한다.
     *  - 전송은 백그라운드(fire-and-forget)로, 실패해도 화면을 막지 않는다. 홈·기록은 진입 시 재조회로 반영한다.
     */
    fun submitExercise(durationMin: Float, safetyNoticeConfirmed: Boolean) {
        if (durationMin <= 0f) return
        if (!safetyNoticeConfirmed) {
            Log.w(TAG, "안전 고지 미확인 — 완료 전송 생략(서버가 400 으로 거부, durationMin=$durationMin)")
            return
        }
        viewModelScope.launch {
            val templateId = exerciseTemplateId ?: resolveExerciseTemplateId() ?: run {
                Log.w(TAG, "운동 미션 템플릿 id 미해석 — 완료 전송 생략(durationMin=$durationMin)")
                return@launch
            }
            try {
                val r = exerciseFlow.submitExerciseSession(
                    missionTemplateId = templateId,
                    durationMin = durationMin,
                    safetyNoticeConfirmed = safetyNoticeConfirmed,
                    createdOnDeviceAt = nowIso8601(),
                )
                Log.i(
                    TAG,
                    "운동 완료 전송 OK: status=${r.finalStatus}, counted=${r.countedForDaily}, dailyTotalMin=${r.dailyTotalMin}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "운동 완료 전송 실패(다음 진입 시 재조회로 보정): ${e.message}")
            }
        }
    }

    /** GET /missions 에서 단일 운동 미션(mission_type=="exercise") 템플릿 id 를 찾아 캐시한다. 실패/부재면 null. */
    private suspend fun resolveExerciseTemplateId(): Int? =
        try {
            missionApi.getMissions().missions
                .firstOrNull { it.missionType == "exercise" }
                ?.missionTemplateId
                ?.also { exerciseTemplateId = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "운동 미션 템플릿 조회 실패: ${e.message}")
            null
        }

    private fun nowIso8601(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    companion object {
        const val TAG = "ExerciseVideos"
    }
}
