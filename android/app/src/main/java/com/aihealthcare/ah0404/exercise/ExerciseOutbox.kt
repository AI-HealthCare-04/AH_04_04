package com.aihealthcare.ah0404.exercise

import android.content.Context
import android.util.Log
import com.aihealthcare.ah0404.network.SessionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 전송에 아직 성공하지 못한 운동 세션 한 건 — 같은 자연 키로 다시 시도할 최소 정보(#234).
 *  영속 outbox(#271)에 JSON 으로 저장/복원하므로 @Serializable. (이전엔 VM 내부 인메모리 타입이었다.)
 */
@Serializable
data class PendingExercise(
    val durationMin: Float,
    val createdOnDeviceAt: String,
    val safetyNoticeConfirmed: Boolean,
)

/**
 * 미전송 운동 세션의 영속 저장소(#271, #234 후속).
 *
 *  #258 은 pending 을 **프로세스 메모리에만** 두어, 전송이 아직 성공하지 못한 세션이 남은 채 앱이
 *  완전히 종료되면(스와이프 종료·OS 회수) 그 세션이 소실됐다(사용자는 완료했다고 느끼지만 집계 누락).
 *  이 포트는 그 pending 집합을 로컬에 영속화해 **앱 재시작·네트워크 복구 시 flush** 할 수 있게 한다.
 *  성공한 키만 제거하는 계약은 인메모리 로직과 동일 — VM 이 pending 을 바꿀 때마다 [save] 로 스냅샷을 남긴다.
 */
interface ExerciseOutbox {
    /** 저장된 미전송 세션(삽입 순서 유지 — 오래된 것부터 재시도). 없으면 빈 목록. */
    fun load(): List<PendingExercise>

    /** 현재 pending 스냅샷으로 저장소를 덮어쓴다(성공으로 빠진 키는 자연히 사라진다). */
    fun save(sessions: List<PendingExercise>)
}

/**
 * 영속하지 않는 기본 구현 — 인메모리 동작(앱 재시작 시 소실)을 그대로 유지한다.
 *  주입 없이 만든 VM(테스트/프리뷰)과 계약이 동일하다: 저장은 무시하고 로드는 항상 비어 있다.
 */
class NoOpExerciseOutbox : ExerciseOutbox {
    override fun load(): List<PendingExercise> = emptyList()
    override fun save(sessions: List<PendingExercise>) = Unit
}

/**
 * SharedPreferences 기반 영속 outbox.
 *
 *  ⚠️ **사용자 스코프**(오배분 방지): 재시작 후 flush 는 *현재* 세션 토큰으로 전송되므로, 저장은
 *     계정이 재시작을 넘겨 유지되는 사용자([SessionStore.persistentUserId])에게만 한다.
 *     게스트/비로그인(persistentUserId==null)은 no-op → 인메모리 동작 유지(게스트 토큰도 재시작 시 소실되니
 *     그 세션을 다음 사용자에게 잘못 붙이지 않는다). 로그인 사용자만 별 키(`user_<id>_pending`)로 분리 저장한다.
 *
 *  값이 손상돼도(구버전 포맷 등) 예외를 화면까지 올리지 않고 해당 키를 폐기하고 빈 목록으로 폴백한다
 *  ([PetBubbleVisitStore] 와 같은 방침). 무한 증가 방지로 최신 [MAX_PENDING] 건만 남긴다(초과분 로그).
 */
class SharedPrefsExerciseOutbox(context: Context) : ExerciseOutbox {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<PendingExercise> {
        val userId = SessionStore.persistentUserId ?: return emptyList()
        return runCatching {
            val raw = preferences.getString(key(userId), null) ?: return emptyList()
            json.decodeFromString<List<PendingExercise>>(raw)
        }.getOrElse {
            Log.w(TAG, "미전송 운동 세션 복원 실패 — 해당 키 폐기: ${it.message}")
            clear(userId)
            emptyList()
        }
    }

    override fun save(sessions: List<PendingExercise>) {
        val userId = SessionStore.persistentUserId ?: return
        // 최신 것 우선으로 상한을 둔다(오래된 것부터 버림) — 계속 실패하며 오프라인 운동이 쌓여도 prefs 가 무한 증가하지 않게.
        val capped = if (sessions.size > MAX_PENDING) {
            Log.w(TAG, "미전송 운동 세션 ${sessions.size}건 — 상한 $MAX_PENDING 초과분(오래된 것) 폐기")
            sessions.takeLast(MAX_PENDING)
        } else {
            sessions
        }
        runCatching {
            if (capped.isEmpty()) {
                preferences.edit().remove(key(userId)).apply()
            } else {
                preferences.edit().putString(key(userId), json.encodeToString(capped)).apply()
            }
        }.onFailure { Log.w(TAG, "미전송 운동 세션 저장 실패: ${it.message}") }
    }

    private fun clear(userId: Int) {
        runCatching { preferences.edit().remove(key(userId)).apply() }
    }

    private fun key(userId: Int) = "user_${userId}_pending"

    companion object {
        private const val PREFS = "exercise_outbox"
        private const val TAG = "ExerciseOutbox"
        private const val MAX_PENDING = 50
        private val json = Json { ignoreUnknownKeys = true }
    }
}
