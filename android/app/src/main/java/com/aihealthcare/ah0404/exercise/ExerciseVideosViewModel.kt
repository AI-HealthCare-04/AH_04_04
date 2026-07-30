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

    // 운동 세션 자연 키(#158, ISO-8601). 재생 시작 시 1회 잡아 완료 전송·재시도까지 **같은 값**을 쓴다:
    //   같은 키면 서버가 중복 집계를 막고(#158), POST 성공/PATCH 실패로 in_progress 만 남아도 재시도가 그
    //   로그를 완료로 되살린다(#172). 재시도마다 새 키를 만들면 중복이 되므로 고정한다(리뷰 #234-1).
    //   새 재생 시작([beginExerciseSession])마다 새 키로 교체한다 — 세션이 다르면 키도 달라야 하니까.
    private var sessionCreatedAt: String? = null

    // 전송 실패로 아직 서버에 안 남은 세션(수행 분 + 고정 키). 화면 재개 등에서 **같은 키**로 재시도한다(리뷰 #234-2).
    //   메모리 보존이라 앱 재시작 시 소실 — 영속 outbox 는 후속 이슈. 여기선 세션 생명주기 내 복구만 보장한다.
    var pendingResend by mutableStateOf<PendingExercise?>(null); private set

    /** 전송 실패로 보존된 운동 세션 — 같은 자연 키로 다시 시도할 최소 정보. */
    data class PendingExercise(
        val durationMin: Float,
        val createdOnDeviceAt: String,
        val safetyNoticeConfirmed: Boolean,
    )

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
     * 운동 재생을 **시작**할 때 호출 — 이 세션의 자연 키를 새로 확정한다(리뷰 #234-1).
     *  재생 시작마다 새 키로 교체한다: 세션이 다르면(다른 영상/루틴을 새로 시작) 키도 달라야 서버가
     *  별개 세션으로 합산한다. 반대로 한 세션의 완료 전송이 실패해 재시도할 땐 [submitExercise]/[retryPendingResend]
     *  가 이 시점에 잡힌 **같은 키**를 재사용하므로 중복 집계되지 않는다.
     */
    fun beginExerciseSession() {
        sessionCreatedAt = nowIso8601()
    }

    /**
     * 운동 세션 한 건(스트리밍 시청 분 또는 루틴 진행 분)의 완료를 서버에 올린다(#234).
     *
     *  - **0분 이하**: 서버 ExerciseDetail.duration_min 은 gt=0(당일 누적 되돌리기 방어)이라, 즉시 이탈 등
     *    0분 세션은 아예 보내지 않고 조용히 무시한다 → 호출부(영상/루틴)는 이 가드를 믿고 콜백을 자유롭게 불러도 된다.
     *  - **안전 고지 미확인**: 운동은 서버가 확인을 요구하므로(true 아니면 400), 확인 게이트를 통과하지 않았으면
     *    보내지 않는다. [safetyNoticeConfirmed] 는 호출부(화면의 확인 게이트)가 넘긴 **실제 확인 결과**다.
     *  - 자연 키는 [beginExerciseSession] 에서 잡은 값을 쓴다(없으면 지금 확정). 전송은 백그라운드(fire-and-forget)로,
     *    실패해도 화면을 막지 않되 [pendingResend] 에 **같은 키로** 보존해 재개/재시도로 복구한다([retryPendingResend]).
     */
    fun submitExercise(durationMin: Float, safetyNoticeConfirmed: Boolean) {
        if (durationMin <= 0f) return
        if (!safetyNoticeConfirmed) {
            Log.w(TAG, "안전 고지 미확인 — 완료 전송 생략(서버가 400 으로 거부, durationMin=$durationMin)")
            return
        }
        // 세션 시작에서 못 잡았으면(직접 호출/테스트) 지금 확정하고, 재시도까지 이 값을 재사용한다.
        val key = sessionCreatedAt ?: nowIso8601().also { sessionCreatedAt = it }
        send(durationMin, key, safetyNoticeConfirmed)
    }

    /**
     * 전송 실패로 보존된([pendingResend]) 세션을 **같은 자연 키**로 다시 시도한다(화면 재개/사용자 재시도, 리뷰 #234-2).
     *  같은 키라 서버가 이미 저장했으면 중복 없이 끝나고, in_progress 만 남았으면 완료로 되살린다(#172). 없으면 무시.
     */
    fun retryPendingResend() {
        val p = pendingResend ?: return
        send(p.durationMin, p.createdOnDeviceAt, p.safetyNoticeConfirmed)
    }

    private fun send(durationMin: Float, createdOnDeviceAt: String, safetyNoticeConfirmed: Boolean) {
        viewModelScope.launch {
            val templateId = exerciseTemplateId ?: resolveExerciseTemplateId() ?: run {
                // 로그인 전/조회 실패로 보낼 대상을 못 얻음 — 잃지 않게 보존해 이후 재시도(로그인·복구)에 맡긴다.
                Log.w(TAG, "운동 미션 템플릿 id 미해석 — 재시도 대기로 보존(durationMin=$durationMin)")
                pendingResend = PendingExercise(durationMin, createdOnDeviceAt, safetyNoticeConfirmed)
                return@launch
            }
            try {
                val r = exerciseFlow.submitExerciseSession(
                    missionTemplateId = templateId,
                    durationMin = durationMin,
                    safetyNoticeConfirmed = safetyNoticeConfirmed,
                    createdOnDeviceAt = createdOnDeviceAt,
                )
                pendingResend = null // 서버에 안전히 남음 — 보존 해제
                Log.i(
                    TAG,
                    "운동 완료 전송 OK: status=${r.finalStatus}, counted=${r.countedForDaily}, dailyTotalMin=${r.dailyTotalMin}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 재조회는 서버에 없는 시간을 복원하지 못한다 → 같은 키를 보존해 실제로 재시도할 수 있게 한다(문구 정정).
                pendingResend = PendingExercise(durationMin, createdOnDeviceAt, safetyNoticeConfirmed)
                Log.w(TAG, "운동 완료 전송 실패 — 같은 키로 재시도 대기(durationMin=$durationMin): ${e.message}")
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
