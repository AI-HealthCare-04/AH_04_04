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
    // 미전송 세션의 영속 저장소(#271). 기본은 영속하지 않는 NoOp(인메모리 동작 유지) — 실제 화면은 SharedPrefs 구현을 주입한다.
    private val outbox: ExerciseOutbox = NoOpExerciseOutbox(),
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
    // 직전 발급 키의 ms — 같은 밀리초 안에 두 세션이 시작돼도(빠른 연속/테스트) 키가 겹치지 않게 단조 증가시킨다.
    private var lastKeyMillis = 0L

    // 전송에 아직 성공하지 못한 세션들 — **자연 키별**로 보존한다(리뷰 #234 재검토: 단일 슬롯은 A 실패 후 B 성공 시
    //   A 까지 지워 유실). 성공하면 **그 키만** 지우고 나머지는 남긴다. 삽입 순서 유지(LinkedHashMap)로 오래된 것부터 재시도.
    //   [outbox] 로 영속화(#271)하므로 앱 재시작 시에도 남아, init 에서 되살려 재시도한다(과거엔 메모리 소실).
    private val pending = LinkedHashMap<String, PendingExercise>()
    // 지금 전송 중인 키(중복 동시 전송 방지) — ON_RESUME/복귀 재시도가 진행 중 전송과 겹쳐 이중 전송되는 것을 막는다.
    private val inFlight = mutableSetOf<String>()
    // 보존된 세션 스냅샷(관찰/테스트용). [pending] 이 바뀔 때마다 갱신한다.
    var pendingResends by mutableStateOf<List<PendingExercise>>(emptyList()); private set

    init {
        // 이전 실행에서 전송 못 하고 종료된 세션들을 되살려(영속 outbox, #271) 재시도한다 — "앱 재시작 시 flush".
        //   로그인 사용자만 저장돼 있으므로 현재 세션 토큰으로 올바르게 붙는다(오배분은 SharedPrefsExerciseOutbox 가 스코프로 방어).
        //   미로그인/템플릿 미해석이면 send 가 pending 을 그대로 두어, 이후 복구(로그인·ON_RESUME)에서 다시 시도한다.
        val restored = outbox.load()
        if (restored.isNotEmpty()) {
            restored.forEach { pending[it.createdOnDeviceAt] = it }
            pendingResends = pending.values.toList()
            viewModelScope.launch { retryPending() }
        }
    }

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
     *  별개 세션으로 합산한다. 반대로 한 세션의 완료 전송이 실패해 재시도할 땐 [submitExercise]/[retryPending]
     *  이 이 시점에 잡힌 **같은 키**를 재사용하므로 중복 집계되지 않는다.
     */
    fun beginExerciseSession() {
        sessionCreatedAt = nextSessionKey()
    }

    /**
     * 운동 세션 한 건(스트리밍 시청 분 또는 루틴 진행 분)의 완료를 서버에 올린다(#234).
     *
     *  - **0분 이하**: 서버 ExerciseDetail.duration_min 은 gt=0(당일 누적 되돌리기 방어)이라, 즉시 이탈 등
     *    0분 세션은 아예 보내지 않고 조용히 무시한다 → 호출부(영상/루틴)는 이 가드를 믿고 콜백을 자유롭게 불러도 된다.
     *  - **안전 고지 미확인**: 운동은 서버가 확인을 요구하므로(true 아니면 400), 확인 게이트를 통과하지 않았으면
     *    보내지 않는다. [safetyNoticeConfirmed] 는 호출부(화면의 확인 게이트)가 넘긴 **실제 확인 결과**다.
     *  - 자연 키는 [beginExerciseSession] 에서 잡은 값을 쓴다(없으면 지금 확정). 전송은 백그라운드로, 성공 전까지
     *    [pending] 에 **키별로** 보존해(다른 세션을 덮어쓰지 않음) 재개/복귀 시 [retryPending] 으로 복구한다.
     */
    fun submitExercise(durationMin: Float, safetyNoticeConfirmed: Boolean) {
        if (durationMin <= 0f) return
        if (!safetyNoticeConfirmed) {
            Log.w(TAG, "안전 고지 미확인 — 완료 전송 생략(서버가 400 으로 거부, durationMin=$durationMin)")
            return
        }
        // 세션 시작에서 못 잡았으면(직접 호출/테스트) 지금 확정하고, 재시도까지 이 값을 재사용한다.
        val key = sessionCreatedAt ?: nextSessionKey().also { sessionCreatedAt = it }
        send(PendingExercise(durationMin, key, safetyNoticeConfirmed))
    }

    /**
     * 아직 서버에 성공하지 못한 세션들을 **각자의 자연 키**로 다시 시도한다(화면 복귀/ON_RESUME, 리뷰 #234 재검토).
     *  같은 키라 서버가 이미 저장했으면 중복 없이 끝나고, in_progress 만 남았으면 완료로 되살린다(#172).
     *  전송 중인 키는 [send] 의 in-flight 가드가 걸러 이중 전송하지 않는다. 남은 게 없으면 no-op.
     */
    fun retryPending() {
        pending.values.toList().forEach { send(it) }
    }

    /**
     * 세션 한 건을 서버에 올린다. 성공 전까지 [pending] 에 키별로 보존하고, **성공하면 그 키만** 지운다
     *  → A 실패 후 B 가 성공해도 A 는 남는다(단일 슬롯 유실 방지). 같은 키의 동시 전송은 [inFlight] 로 막는다.
     */
    private fun send(session: PendingExercise) {
        val key = session.createdOnDeviceAt
        if (!inFlight.add(key)) return // 이 키가 이미 전송 중 — 이중 전송 방지
        // 성공 전까지 보존(재시도 대상). 실패해도 이미 들어가 있으니 별도 저장이 필요 없다.
        pending[key] = session
        publishPending()
        viewModelScope.launch {
            try {
                val templateId = exerciseTemplateId ?: resolveExerciseTemplateId() ?: run {
                    // 로그인 전/조회 실패로 보낼 대상을 못 얻음 — 보존한 채 이후 재시도(로그인·복구)에 맡긴다.
                    Log.w(TAG, "운동 미션 템플릿 id 미해석 — 재시도 대기로 보존(durationMin=${session.durationMin})")
                    return@launch
                }
                val r = exerciseFlow.submitExerciseSession(
                    missionTemplateId = templateId,
                    durationMin = session.durationMin,
                    safetyNoticeConfirmed = session.safetyNoticeConfirmed,
                    createdOnDeviceAt = key,
                )
                pending.remove(key) // 이 키만 서버에 안전히 남음 — 보존 해제(다른 세션은 유지)
                publishPending()
                Log.i(
                    TAG,
                    "운동 완료 전송 OK: status=${r.finalStatus}, counted=${r.countedForDaily}, dailyTotalMin=${r.dailyTotalMin}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 재조회는 서버에 없는 시간을 복원하지 못한다 → 같은 키를 보존해 실제로 재시도할 수 있게 둔다(문구 정정).
                Log.w(TAG, "운동 완료 전송 실패 — 같은 키로 재시도 대기(durationMin=${session.durationMin}): ${e.message}")
            } finally {
                inFlight.remove(key) // 전송 종료 — 다음 재시도가 이 키를 다시 시도할 수 있게 해제
            }
        }
    }

    /** [pending] 변경을 관찰 상태에 반영하고 **영속 outbox 에도 스냅샷을 남긴다**(#271) — 전송 중 앱이 종료돼도 세션이 남게. */
    private fun publishPending() {
        val snapshot = pending.values.toList()
        pendingResends = snapshot
        outbox.save(snapshot)
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

    /**
     * 이번 세션의 자연 키(ISO-8601). 직전 키와 같은 밀리초면 +1ms 로 단조 증가시켜 **키 충돌을 막는다**:
     *  실제로는 세션이 초 단위로 떨어져 그대로지만, 같은 ms 안에 두 세션이 시작돼도(빠른 연속/테스트)
     *  키가 겹쳐 pending 맵에서 서로를 덮어쓰는 일이 없게 한다(리뷰 재검토의 유실 경로 방어).
     */
    private fun nextSessionKey(): String {
        var millis = System.currentTimeMillis()
        if (millis <= lastKeyMillis) millis = lastKeyMillis + 1
        lastKeyMillis = millis
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))
    }

    companion object {
        const val TAG = "ExerciseVideos"
    }
}
