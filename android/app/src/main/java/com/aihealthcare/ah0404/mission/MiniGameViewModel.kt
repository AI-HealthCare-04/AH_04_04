package com.aihealthcare.ah0404.mission

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.retrofit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 미니게임 완료 기록(#348 리뷰) — 영상 완주 시 게임 미션 완료를 서버에 기록한다.
 *
 *  게임은 즉시완료 유형(#93)이라 POST /mission-logs(status=completed) 한 번이면 되고, 하루 1회
 *  상한(#272)은 서버가 counted_for_daily 로 판정한다. 이 VM 이 게임 완료의 **유일한 기록 지점**(#91)이다.
 *
 *  실패 대응(#348 2차 — 완주·포인트 영구 유실 방지):
 *   1. 짧은 백오프로 제한 재시도([RETRY_DELAYS_MS]) — 순간적인 네트워크 흔들림을 흡수한다.
 *      재전송이 **중복 적립되지 않는 근거는 자연 키**다(#158): 완주 시각(createdOnDeviceAt)을
 *      한 번 만들어 모든 재시도·pending 복구에서 되쏘면, 서버가 (user, template, 그 시각)으로
 *      같은 수행임을 알아보고 기존 로그를 그대로 돌려준다.
 *      ⚠️ 이 키가 없으면 게임은 `counted_for_daily = success` 라 서버 상한이 따로 없어,
 *        첫 POST 가 저장되고 응답만 유실된 재시도가 **별도 성공 로그로 포인트를 또 적립**한다.
 *   2. 성공 여부와 무관하게 [onRecorded](목록 재조회)를 호출해 **서버 권위값으로 조정**한다 —
 *      첫 요청이 저장되고 응답만 유실된 경우 재조회에서 today_done=true 로 배지가 살아난다.
 *   3. 최종 실패면 완주를 pending 으로 보관하고, 화면 재진입 시 [retryPendingIfAny] 로 복구 재시도한다.
 *  자동 복귀(#344)는 어떤 경우에도 막지 않는다(기록은 백그라운드).
 */
class MiniGameViewModel(
    // 테스트 주입용 기본 인자 — 프로덕션은 공용 retrofit.
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
) : ViewModel() {

    private var inFlight = false

    /**
     * 최종 실패한 완주 기록(#348 2차). 화면 재진입 시 복구 재시도 대상.
     * **자연 키를 함께 보관**한다 — 복구 재시도가 새 시각을 만들면 서버가 다른 수행으로 보고
     * 중복 적립한다(#379 리뷰).
     */
    private var pending: PendingCompletion? = null

    private data class PendingCompletion(val mission: Mission, val createdOnDeviceAt: String)

    fun recordCompletion(mission: Mission, onRecorded: () -> Unit) {
        if (inFlight) return // 완주 콜백 중복 발화 방어(리스너 재부착 등)
        // 자연 키는 **이 완주 1회에 대해 한 번만** 만든다(#158). 재시도 루프 안에서 만들면 시도마다
        //   값이 달라져 서버가 서로 다른 수행으로 받아들이고 포인트가 중복 적립된다.
        record(PendingCompletion(mission, nowIso8601()), onRecorded)
    }

    /** 화면 재진입 시 미기록 완주 복구(#348 2차). pending 이 없으면 무동작. */
    fun retryPendingIfAny(onRecorded: () -> Unit) {
        // 보관해 둔 자연 키를 그대로 재사용한다 — 첫 시도가 이미 저장됐다면 서버가 같은 수행으로 판별한다.
        val held = pending ?: return
        record(held, onRecorded)
    }

    private fun record(completion: PendingCompletion, onRecorded: () -> Unit) {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            try {
                val saved = postWithRetry(completion)
                pending = if (saved) null else completion
                // 성공/유실/실패 모두 목록 재조회로 조정(#348 2차): 서버가 진실원천이다.
                onRecorded()
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun postWithRetry(completion: PendingCompletion): Boolean {
        RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            try {
                api.createMissionLog(
                    MissionLogCreateRequest(
                        missionTemplateId = completion.mission.missionTemplateId,
                        missionType = "game",
                        status = "completed",
                        success = true, // 서버 적립 판정 필수(counted_for_daily = success) — 누락이 비적립 원인이었다
                        // 재전송 중복 제거의 자연 키(#158). 모든 재시도가 같은 값을 보낸다.
                        createdOnDeviceAt = completion.createdOnDeviceAt,
                    )
                )
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "게임 완료 기록 실패(${attempt + 1}/${RETRY_DELAYS_MS.size}): ${e.message}")
                if (delayMs > 0) delay(delayMs)
            }
        }
        return false
    }

    /** 걷기(WalkingSessionViewModel)와 동일 포맷 — 서버가 KST naive 로 파싱한다. */
    private fun nowIso8601(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    private companion object {
        const val TAG = "MiniGame"

        /** 시도별 후속 대기(ms). 마지막 원소 0 = 마지막 시도 후 대기 없음 — 총 3회. */
        val RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 0L)
    }
}
