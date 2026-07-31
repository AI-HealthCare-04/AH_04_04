package com.aihealthcare.ah0404.record

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.dashboard.DashboardPrefill
import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.PredictionInputsResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.network.WalkingDayPoint
import com.aihealthcare.ah0404.network.retrofit
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * `_13 나의 기록` 상태 + 백엔드 배선.
 *
 *  두 소스를 **서로 독립적으로** 조회한다(리뷰 #68 지영 지적 2):
 *   - 연속 예측 추이        → 관리 필요도 점수·변화량·모델 비교 상태.
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

    // 예측 대시보드(#193) 개인화 초기값. null 이면 대시보드가 HTML 기본값으로 열린다(엔드포인트 미배포/미완 시).
    var predictionPrefill by mutableStateOf<DashboardPrefill?>(null); private set

    // 근육 건강 정보(#기록탭 §3·§4) UI 상태(실데이터). load 전엔 null → 화면은 로딩 표시.
    //   MuscleScoreUi 가 internal 이라 프로퍼티도 internal(같은 모듈의 RecordScreen 만 소비).
    internal var muscleScore by mutableStateOf<MuscleScoreUi?>(null); private set

    // ── 나의 기록 챌린지 통계(#기록탭 §5) ─────────────────────────────────────
    var lineLogs by mutableStateOf<List<MissionLogItem>>(emptyList()); private set   // §5.1 최근 14일 완료 선그래프
    var walkingDays by mutableStateOf<List<WalkingDayPoint>>(emptyList()); private set // §5.3 걷기 막대 7일
    var challengeTotals by mutableStateOf<ChallengeTotalsResponse?>(null); private set  // §5.4 도넛
    // §5.2 달력: 표시 중인 월의 스탬프(dateKey→daily_result)와 그 달 완료 미션(팝업용).
    var calYear by mutableStateOf(0); private set
    var calMonth by mutableStateOf(0); private set // 1~12
    var stampsByDate by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var monthLogs by mutableStateOf<List<MissionLogItem>>(emptyList()); private set

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    init {
        val now = GregorianCalendar(kst)
        calYear = now.get(Calendar.YEAR)
        calMonth = now.get(Calendar.MONTH) + 1
    }

    // 겹친 refresh 중 최신 것만 상태를 commit 하도록 식별하는 세대 토큰.
    private var generation = 0

    // 겹친 월 조회(loadMonth) 중 최신 것만 commit 하도록 식별하는 세대 토큰(리뷰 #302 — 계정 전환·재진입 시
    //   같은 달을 보던 이전 응답이 새 화면을 덮지 않게). refresh 와 독립.
    private var monthGeneration = 0

    // 계정 전환 시 이전 사용자 데이터 격리는 MainActivity 가 MAIN VM 저장소를 SessionStore.authRevision 마다
    //   새로 만들어(#328) 구조적으로 처리한다 — 이 VM 도 계정이 바뀌면 새 인스턴스로 재생성되므로, 여기서
    //   별도 초기화 로직을 두지 않는다.
    fun load() {
        viewModelScope.launch { refresh() }
        loadMonth(calYear, calMonth)
    }

    /** 달력 월 이동(#기록탭 §5.2). 그 달의 스탬프 + 완료 미션(팝업용)을 다시 불러온다. */
    fun showPreviousMonth() {
        val c = GregorianCalendar(kst).apply { clear(); set(calYear, calMonth - 1, 1); add(Calendar.MONTH, -1) }
        calYear = c.get(Calendar.YEAR); calMonth = c.get(Calendar.MONTH) + 1
        loadMonth(calYear, calMonth)
    }

    fun showNextMonth() {
        val c = GregorianCalendar(kst).apply { clear(); set(calYear, calMonth - 1, 1); add(Calendar.MONTH, 1) }
        calYear = c.get(Calendar.YEAR); calMonth = c.get(Calendar.MONTH) + 1
        loadMonth(calYear, calMonth)
    }

    private fun loadMonth(year: Int, month1: Int) {
        val gen = ++monthGeneration
        viewModelScope.launch {
            val (from, to) = monthBounds(year, month1)
            val month = String.format(Locale.US, "%04d-%02d", year, month1)
            val stampsResult = safeCall { api.getStamps(month).days }
            val logsResult = safeCall { api.getMissionLogs(from = from, to = to).logs }
            // 이 조회 이후 다른 loadMonth(달 이동·재진입·계정 전환 후 재load)가 시작됐으면 낡은 응답은 버린다(리뷰 #302).
            //   달 번호만 비교하던 기존 가드는 '같은 달을 보던 이전 사용자'의 늦은 stamps/logs 응답이 계정 전환 후
            //   새 사용자 화면에 반영되는 경로를 못 막는다 → refresh() 와 같은 세대(generation) 토큰으로 가장 최근
            //   loadMonth 만 commit 한다. (계정 전환 시 VM 자체가 파기되는 #328 과 별개로, VM 관측 가능한 방어.)
            if (gen != monthGeneration) return@launch
            stampsResult.onSuccess { days -> stampsByDate = days.associate { it.date to it.dailyResult } }
                .onFailure { Log.w(TAG, "스탬프 조회 실패: ${it.message}") }
            logsResult.onSuccess { monthLogs = it }
                .onFailure { Log.w(TAG, "달 미션 로그 조회 실패: ${it.message}") }
        }
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
            // §5.1 선그래프: 최근 14일 완료 미션. §5.4 도넛·§5.3 걷기 막대는 각자 소스.
            val lineCall = async { safeCall { api.getMissionLogs(from = daysAgoKey(13), to = todayKey()).logs } }
            val walkingCall = async { safeCall { api.getWalkingDaily(7).days } }
            val totalsCall = async { safeCall { api.getChallengeTotals() } }
            // 예측 대시보드 개인화(#193): 실패해도(미배포/프로필 미완) 화면은 막지 않고 기본값 폴백.
            val prefillCall = async { safeCall { api.getPredictionInputs() } }
            // 근육 건강 정보(§3·§4) 실데이터. 미배포/미예측(404)이면 null → "준비 중"·연령 카드로 폴백.
            val latestCall = async { safeCall { api.getLatestPrediction() } }
            val simCall = async { safeCall { api.getScoreSimulation() } }
            // 또래 분포(#193): 실패(미탑재·65세 미만·구버전)해도 차트만 미표시 — 다른 섹션과 독립.
            val cohortCall = async { safeCall { api.getCohortDistribution() } }
            val historyResult = historyCall.await()
            val lineResult = lineCall.await()
            val walkingResult = walkingCall.await()
            val totalsResult = totalsCall.await()
            val prefillResult = prefillCall.await()
            val latestResult = latestCall.await()
            val simResult = simCall.await()
            val cohortResult = cohortCall.await()

            // 이 refresh 이후 더 최신 refresh 가 시작됐다면, 낡은 결과는 버린다(commit 안 함).
            if (gen != generation) return@coroutineScope

            historyResult
                .onSuccess { history = it }
                .onFailure { historyError = true; Log.w(TAG, "예측 추이 조회 실패: ${it.message}") }
            lineResult
                .onSuccess { logs ->
                    lineLogs = logs
                    // "완료한 미션 수" = 실제 완료 집계된 미션 수(#274). success 가 아니라 counted_for_daily:
                    //   운동·걷기(누적 목표)는 목표를 넘긴 뒤의 세션도 success=true 지만 counted_for_daily=false
                    //   (미적립) → success 로 세면 반복 세션이 부풀려진다. counted 는 홈 완료 개수·포인트와 일관.
                    completedMissions = logs.count { it.countedForDaily }
                    totalPoints = logs.sumOf { it.earnedPoints }
                }
                .onFailure { activityError = true; Log.w(TAG, "미션 로그 조회 실패: ${it.message}") }
            walkingResult.onSuccess { walkingDays = it }
                .onFailure { Log.w(TAG, "걷기 일별 조회 실패: ${it.message}") }
            totalsResult.onSuccess { challengeTotals = it }
                .onFailure { Log.w(TAG, "챌린지 집계 조회 실패: ${it.message}") }
            prefillResult
                .onSuccess { predictionPrefill = it.toDashboardPrefill() }
                .onFailure { Log.w(TAG, "예측 입력 조회 실패(기본값 폴백): ${it.message}") }
            simResult.onFailure { Log.w(TAG, "점수 시뮬레이션 조회 실패: ${it.message}") }
            latestResult.onFailure { Log.w(TAG, "근육 건강 점수 조회 실패: ${it.message}") }
            cohortResult.onFailure { Log.w(TAG, "또래 분포 조회 실패(차트 미표시): ${it.message}") }
            // 근육 건강 정보 UI 상태(§3·§4)는 실데이터로 구성한다 — 앱은 점수를 계산하지 않는다(서버 값 표시만).
            //   5STS(초)는 아직 노출 API가 없어(백엔드 필요) stsSeconds=null → §3.4 안전망 카드는 미표시.
            muscleScore = MuscleScoreUi(
                age = predictionPrefill?.age,
                score = latestResult.getOrNull()?.muscleScore,
                band = latestResult.getOrNull()?.scoreBand,
                // 리뷰 #275-②: 비교 불가 경계(model_changed·cohort_version 변경)를 보존한 추이.
                trend = buildScoreTrend(history),
                walkSim = simResult.getOrNull()?.walk?.mapNotNull { p -> p.score?.let { ScoreSimPoint(p.days, it) } } ?: emptyList(),
                muscSim = simResult.getOrNull()?.musc?.mapNotNull { p -> p.score?.let { ScoreSimPoint(p.days, it) } } ?: emptyList(),
                stsSeconds = null,
                bmi = null,
                cohort = cohortResult.getOrNull(),
            )
            loaded = true
        }
        if (gen == generation) loading = false
    }

    private fun dateKeyMillis(millis: Long): String {
        val c = GregorianCalendar(kst).apply { timeInMillis = millis }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun todayKey(): String = dateKeyMillis(System.currentTimeMillis())
    private fun daysAgoKey(days: Int): String = dateKeyMillis(System.currentTimeMillis() - days.toLong() * 86_400_000L)

    private fun monthBounds(year: Int, month1: Int): Pair<String, String> {
        val first = GregorianCalendar(kst).apply { clear(); set(year, month1 - 1, 1) }
        val last = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-01", year, month1) to
            String.format(Locale.US, "%04d-%02d-%02d", year, month1, last)
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

/** 서버 응답 → 대시보드 주입값(#193). 성별 코드화·생년→나이·소수 반올림. 값 없으면 null(HTML 기본값 유지). */
private fun PredictionInputsResponse.toDashboardPrefill(): DashboardPrefill = DashboardPrefill(
    sex = when (sex) { "male" -> 1; "female" -> 2; else -> null },
    age = birthDate?.let(::manAgeFromIso)?.takeIf { it in 1..120 },
    heightCm = heightCm?.roundToInt(),
    weightKg = weightKg?.roundToInt(),
    waistCm = waistCm?.roundToInt(),
    walkDays = walkDays,
    muscDays = muscDays,
)

/**
 * "YYYY-MM-DD" 생년월일 → **만 나이**. 연도만 빼면 생일 전 사용자가 1살 많게 나오므로(리뷰 반영),
 * 올해 생일이 아직 안 지났으면 -1 한다. java.time(API26+) 대신 Calendar(minSdk 24) 사용.
 */
private fun manAgeFromIso(iso: String): Int? {
    val parts = iso.split("-")
    if (parts.size < 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].take(2).toIntOrNull() ?: return null
    val now = Calendar.getInstance()
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) + 1 // Calendar.MONTH 는 0-based
    val curDay = now.get(Calendar.DAY_OF_MONTH)
    var age = curYear - year
    if (curMonth < month || (curMonth == month && curDay < day)) age-- // 올해 생일 전이면 -1
    return age
}
