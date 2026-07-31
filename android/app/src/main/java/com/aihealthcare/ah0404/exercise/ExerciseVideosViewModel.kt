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
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // 현재 인증 주체를 나타내는 revision(리뷰 #291, 시니어 공용 단말). 이 VM 은 Activity 범위라 로그아웃→타계정
    //   로그인 시 재사용될 수 있어, 주체가 바뀌면 이전 사용자 파생 상태(오늘 누적 등)를 비워야 데이터가 섞이지 않는다.
    //   `persistentUserId`(완료 소셜만·게스트는 null)와 달리 revision 은 로그인/로그아웃마다 증가해 게스트↔게스트
    //   전환까지 구분한다(리뷰 #291-1). 테스트는 람다로 계정 전환을 주입한다.
    private val authKey: () -> Int = { SessionStore.authRevision },
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
    // 완료 전송 직렬화 락(#235, 리뷰 #280) — 여러 send()가 병렬 launch 돼도 전송+상태적용을 한 번에 하나씩 순서대로
    //   처리해, 서버 처리순서=적용순서를 보장한다(누적값이 역순 응답·자정 경계로 되돌아가지 않게). fair mutex(FIFO).
    private val completionMutex = Mutex()
    // 보존된 세션 스냅샷(관찰/테스트용). [pending] 이 바뀔 때마다 갱신한다.
    var pendingResends by mutableStateOf<List<PendingExercise>>(emptyList()); private set

    // 이어보기(#235): url 별 마지막 재생 위치(ms). 전체화면을 닫았다 다시 열어도 처음부터가 아니라 이어서 재생한다.
    //   세션 내 복귀용이라 메모리 보관(앱 재시작까지 보존할 필요는 #235 범위 밖). 영상 완주 시엔 0 이 저장돼 다음엔 처음부터.
    private val positionByUrl = mutableMapOf<String, Long>()

    // 오늘 누적 운동 '분'(#235): 서버 당일 합산값(sum_exercise_minutes_today). 진입 시엔 목록 GET 의 운동
    //   today_progress 로, 완료 후엔 완료 응답으로 채운다(둘 다 같은 서버 권위값). 앱이 직접 더하지 않는다.
    //   운동 미션이 없거나 구버전 서버(필드 부재)면 null(종전대로 미표시).
    var todayExerciseMin by mutableStateOf<Float?>(null); private set
    // 오늘 운동 목표(하루 10분, #168)를 채웠는지 — 서버 판정(success). 완료 판정을 사용자가 확인할 수 있게 한다(#235 핵심).
    var todayGoalReached by mutableStateOf(false); private set

    // 완료 전송이 오늘 누적을 적용할 때마다 증가하는 리비전(리뷰 #291 블로커1). 진입 목록 GET([loadTodayProgress])이
    //   느리게 돌아오는 사이 완료 전송([send])이 최신 누적을 적용했다면, 조회 시작 시점 리비전과 달라져 오래된 GET 값을
    //   폐기한다 — 중간 이탈 후 재진입 경로에서 늦은 GET 이 최신 완료값(예: 10분)을 과거값(6분)으로 되돌리는 것 방지.
    //   completionMutex 안에서만 읽고/증가시켜 [send] 의 적용과 순서를 맞춘다(단일 스레드 confinement + 락으로 경합 없음).
    private var progressRevision = 0

    // 지금 화면에 반영된 오늘 누적이 '어느 인증 주체' 것인지(리뷰 #291). [load] 진입 시 [authKey] 와 비교해 주체가
    //   바뀌었으면 어떤 네트워크 호출보다 먼저(동기 구간) 이전 사용자 파생 상태를 비운다 — 영상/목록 API 가 느려도
    //   B 화면에 A 값이 한 번도 노출되지 않게(리뷰 #291-2). 최초 1회는 비교 대상이 없어 [loadedAuthInitialized] 로 구분.
    private var loadedAuthKey = 0
    private var loadedAuthInitialized = false

    /** 이어보기용: 이 url 을 어디부터 재생할지(ms). 없으면 0(처음부터). */
    fun resumePositionFor(url: String): Long = positionByUrl[url] ?: 0L

    /** 이어보기용: 전체화면 이탈 시 마지막 위치를 보관한다(0 이면 처음부터 = 완주했거나 첫 재생). */
    fun saveResumePosition(url: String, positionMs: Long) {
        if (positionMs > 0L) positionByUrl[url] = positionMs else positionByUrl.remove(url)
    }

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
        // 계정 전환 감지·초기화는 어떤 네트워크 호출보다 **먼저**, load 의 동기 구간에서 한다(리뷰 #291-2): 영상/목록
        //   API 가 느려도 새 사용자(B)가 진입한 즉시 이전 사용자(A) 파생 상태가 비워져, A 값이 한 번도 노출되지 않는다.
        resetIfSubjectChanged()
        viewModelScope.launch { refresh() }
    }

    /**
     * 인증 주체(로그인 세션)가 바뀌었으면 이전 사용자 파생 상태를 즉시 비운다(리뷰 #291). [authKey](기본
     *  SessionStore.authRevision)는 로그인/로그아웃마다 증가하므로 게스트↔게스트 전환까지 감지한다(#291-1).
     *  네트워크 이전 [load] 동기 구간에서 호출해 '한 번도 노출되지 않음'을 보장한다(#291-2). 최초 진입은 비교 대상이
     *  없으므로 초기화만 하고 넘어간다.
     */
    private fun resetIfSubjectChanged() {
        val key = authKey()
        if (loadedAuthInitialized && key != loadedAuthKey) {
            clearUserDerivedState()
        }
        loadedAuthKey = key
        loadedAuthInitialized = true
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
        // 진입 시점부터 '오늘까지 N분'을 보여준다(#235 확장): 목록 GET 의 운동 today_progress(서버 당일 합산)를
        //   재사용해, 재생 전에도·중간에 끊었어도 오늘 누적을 확인할 수 있게 한다. 영상 조회와 독립이라 실패해도 무영향.
        loadTodayProgress(gen)
        loaded = true
        loading = false
    }

    /**
     * 오늘 누적 운동시간을 서버 목록(GET /missions 의 운동 today_progress)에서 읽어 **진입 시점부터** 보여준다(#235 확장).
     *  완료 응답([submitExercise])이 오면 더 최신값으로 덮어쓴다. today_progress 가 없거나(구버전 서버) 운동 미션이
     *  없으면 종전대로 미표시(null 유지). 이 조회는 영상 표시와 독립이라 실패해도 화면엔 영향을 주지 않는다.
     *  겸사겸사 단일 운동 템플릿 id 도 캐시해 [submitExercise] 의 별도 조회([resolveExerciseTemplateId])를 아낀다.
     */
    private suspend fun loadTodayProgress(gen: Int) {
        // (계정 전환 감지·초기화는 [load] 동기 구간의 [resetIfSubjectChanged] 에서 네트워크 이전에 끝난다 — 리뷰 #291-2.)
        // 이 조회가 '시작된' 시점의 완료-적용 리비전. 응답이 늦게 오는 사이 완료 전송([send])이 최신 누적을 적용했다면
        //   달라지므로, 오래된 GET 값으로 되돌리지 않도록 폐기 판정에 쓴다(리뷰 #291 블로커1: stale GET 되돌림 방어).
        val revAtStart = progressRevision
        val exercise = try {
            missionApi.getMissions().missions.firstOrNull { it.missionType == "exercise" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "오늘 누적 진행 조회 실패: ${e.message}")
            return
        }
        if (gen != generation) return
        exercise?.missionTemplateId?.let { exerciseTemplateId = it } // 템플릿 id 캐시는 되돌림과 무관 — 락 밖에서 갱신
        // 완료 적용과 순서를 맞춘다(리뷰 #291 블로커1): 완료 전송은 completionMutex 안에서 누적을 적용하므로, 같은 락
        //   안에서 '내 조회 시작 이후 완료가 적용됐는지'를 리비전으로 확인해, 적용됐으면 이 오래된 GET 값을 버린다.
        //   (완료 전송이 진행 중이면 그 적용이 끝날 때까지 여기서 대기했다가 최신 리비전을 보고 판정하므로 경합이 없다.)
        completionMutex.withLock {
            if (progressRevision != revAtStart) {
                Log.i(TAG, "오늘 누적 GET 이 완료 적용보다 늦게 도착 — stale 로 폐기(최신 완료값 유지)")
                return@withLock
            }
            exercise?.todayProgress?.let { p ->
                todayExerciseMin = p.totalMin
                todayGoalReached = p.goalReached
            }
        }
    }

    /**
     * 사용자(인증 주체)가 바뀔 때 이전 사용자 파생 상태를 비운다(리뷰 #291 새 블로커, 시니어 공용 단말).
     *  오늘 누적·달성·운동 템플릿 id 는 모두 특정 사용자에게 종속되므로, 계정 전환 시 남기면 다른 사용자에게 노출된다.
     *  진입 조회 시작 시점에 호출해 새 GET 이 오기 전에 즉시 비운다. (미전송 세션 pending 은 사용자 스코프 outbox(#271)가
     *  별도로 방어한다.) 템플릿 id 는 다음 조회에서 새 사용자 기준으로 다시 해석된다.
     */
    private fun clearUserDerivedState() {
        todayExerciseMin = null
        todayGoalReached = false
        exerciseTemplateId = null
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
        // 이 전송이 시작된 인증 주체(리뷰 #291-3). 응답이 늦게 도착할 때, 그 사이 로그아웃→타계정으로 바뀌었으면
        //   서버 전송·보존은 그대로 두되 **화면 상태만은** 갱신하지 않는다 — A 의 완료 응답이 B 화면에 A 누적을 다시 쓰는 것 방지.
        val epoch = authKey()
        if (!inFlight.add(key)) return // 이 키가 이미 전송 중 — 이중 전송 방지
        // 성공 전까지 보존(재시도 대상). 실패해도 이미 들어가 있으니 별도 저장이 필요 없다.
        pending[key] = session
        publishPending()
        viewModelScope.launch {
            try {
                // 완료 전송을 **직렬화**한다(리뷰 #280): 여러 키의 send()가 병렬 launch 되므로 그냥 두면 PATCH 처리·응답
                //   순서가 뒤섞여 화면 누적값이 되돌아간다. 서버 응답에 날짜/버전이 없어 어떤 응답이 최신 권위값인지 로컬에서
                //   판별할 수 없다(당일 최댓값 방식은 자정 경계에서 전날 값이 남는 회귀가 있었다). Mutex 로 전송+적용을 한 번에
                //   하나씩 순서대로 처리하면 서버 처리순서=전송순서=적용순서가 되어, 마지막 전송값이 곧 최신 권위값이 된다.
                completionMutex.withLock {
                    val templateId = exerciseTemplateId ?: resolveExerciseTemplateId()
                    if (templateId == null) {
                        // 로그인 전/조회 실패로 보낼 대상을 못 얻음 — 보존한 채 이후 재시도(로그인·복구)에 맡긴다.
                        Log.w(TAG, "운동 미션 템플릿 id 미해석 — 재시도 대기로 보존(durationMin=${session.durationMin})")
                        return@withLock
                    }
                    val r = exerciseFlow.submitExerciseSession(
                        missionTemplateId = templateId,
                        durationMin = session.durationMin,
                        safetyNoticeConfirmed = session.safetyNoticeConfirmed,
                        createdOnDeviceAt = key,
                    )
                    pending.remove(key) // 이 키만 서버에 안전히 남음 — 보존 해제(다른 세션은 유지)
                    publishPending()
                    // 누적 운동시간 표시(#235): 직렬화 덕에 이 응답이 지금까지의 마지막 전송 결과 = 최신 권위값이다.
                    //   그대로 대입한다(무조건 last-wins). 당일 내 여러 세션은 마지막이 최댓값이라 자연히 커지고, 자정을 넘긴
                    //   다음 날 첫 세션의 더 작은 누적/미달도 마지막 값이라 정상적으로 초기화된다.
                    //   단, dailyTotalMin==null(재전송 조기종료: 자연 키로 찾은 과거 completed 로그 반환)이면 그 success 는
                    //   '그 로그가 완료됐던 당시' 값이지 오늘 누적의 권위 판정이 아니다(리뷰 #280). 오늘 상태를 오염시키지
                    //   않도록 **누적값이 있을 때만 분·달성을 한 묶음으로** 갱신하고, null 응답은 둘 다 건드리지 않는다.
                    // 화면 상태 갱신은 **전송 시작과 같은 인증 주체일 때만** 한다(리뷰 #291-3): 응답 대기 중 계정이 바뀌었으면
                    //   이 누적은 이전 사용자 것이라 현재(B) 화면에 쓰면 안 된다. 서버 전송·pending 해제는 위에서 이미 끝났다.
                    if (authKey() == epoch) {
                        // 누적 운동시간 표시(#235): 직렬화 덕에 이 응답이 지금까지의 마지막 전송 결과 = 최신 권위값이다.
                        //   그대로 대입한다(무조건 last-wins). 당일 내 여러 세션은 마지막이 최댓값이라 자연히 커지고, 자정을 넘긴
                        //   다음 날 첫 세션의 더 작은 누적/미달도 마지막 값이라 정상적으로 초기화된다.
                        //   단, dailyTotalMin==null(재전송 조기종료: 자연 키로 찾은 과거 completed 로그 반환)이면 그 success 는
                        //   '그 로그가 완료됐던 당시' 값이지 오늘 누적의 권위 판정이 아니다(리뷰 #280). 오늘 상태를 오염시키지
                        //   않도록 **누적값이 있을 때만 분·달성을 한 묶음으로** 갱신하고, null 응답은 둘 다 건드리지 않는다.
                        r.dailyTotalMin?.let { total ->
                            todayExerciseMin = total
                            todayGoalReached = r.success
                            // 오늘 누적을 갱신함 — 진행 중인 오래된 목록 GET([loadTodayProgress]) 결과가 이 값을 되돌리지
                            //   못하게 리비전을 올린다(리뷰 #291 블로커1). 이 대입은 completionMutex 안이라 리비전 증가도 원자적.
                            progressRevision++
                        }
                    } else {
                        Log.i(TAG, "완료 응답이 계정 전환 이후 도착 — 화면 상태는 갱신하지 않음(#291-3, durationMin=${session.durationMin})")
                    }
                    Log.i(
                        TAG,
                        "운동 완료 전송 OK: status=${r.finalStatus}, counted=${r.countedForDaily}, dailyTotalMin=${r.dailyTotalMin}",
                    )
                }
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
