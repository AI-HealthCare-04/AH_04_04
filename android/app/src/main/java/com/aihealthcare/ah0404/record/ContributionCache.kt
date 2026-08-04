package com.aihealthcare.ah0404.record

import android.content.Context
import android.util.Log
import com.aihealthcare.ah0404.network.ContributionItemDto
import com.aihealthcare.ah0404.network.SessionStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 점수 기여도(#406)의 로컬 캐시 — **서버가 사후 조회로는 주지 않기 때문에** 앱이 갖고 있어야 한다.
 *
 *  #411 이 확정한 계약상 기여도는 **예측을 새로 계산하는 순간에만** 응답에 실린다:
 *   - `POST /risk-predictions`(온보딩 create) → 실린다
 *   - `POST /risk-predictions/reassess` 이면서 `recalculated=true` → 실린다
 *   - `GET /risk-predictions/me/latest` → **항상 빈 목록**(서버가 기여도를 저장하지 않는다)
 *   - `POST .../reassess` 이면서 `recalculated=false`(하루 1회 정책 #388) → **빈 목록**
 *
 *  그래서 화면(기록 탭)은 조회 응답이 아니라 이 캐시에서 읽는다. 캐시가 없으면 카드는 그냥 숨는다
 *  (구버전 서버·배포 전 생성된 예측·재설치 직후가 모두 이 경우 — 다음 재계산 때 채워진다).
 *
 *  ⚠️ **빈 목록은 절대 기존 캐시를 지우지 않는다**([save] 가 구조적으로 보장한다). 하루 1회 정책 때문에
 *     같은 날 재평가를 다시 부르면 반드시 `recalculated=false` + 빈 목록이 오는데, 그걸로 덮어쓰면
 *     사용자는 오늘 하루 카드를 잃는다.
 */
interface ContributionCache {
    /** [predictionId] 예측의 기여도. 없으면 빈 목록(= 카드 미표시). */
    fun load(predictionId: Int): List<ContributionItemDto>

    /** 새로 계산된 예측의 기여도를 저장한다. **빈 목록이면 아무것도 하지 않는다**(위 계약). */
    fun save(predictionId: Int, contributions: List<ContributionItemDto>)
}

/**
 * 영속하지 않는 기본 구현 — 주입 없이 만든 VM(테스트/프리뷰)의 계약을 고정한다:
 * 저장은 무시하고 로드는 항상 비어 있다(= 카드 미표시).
 */
class NoOpContributionCache : ContributionCache {
    override fun load(predictionId: Int): List<ContributionItemDto> = emptyList()
    override fun save(predictionId: Int, contributions: List<ContributionItemDto>) = Unit
}

/**
 * SharedPreferences 기반 영속 캐시([SharedPrefsExerciseOutbox] 와 같은 방침).
 *
 *  ⚠️ **사용자 스코프**: 기여도는 건강 상태에서 파생된 값이라 계정 간에 절대 새면 안 된다. 저장은 재시작을
 *     넘겨 유지되는 계정([SessionStore.persistentUserId])에만 하고, 게스트·비로그인은 no-op 이다
 *     (게스트 토큰은 재시작 시 소실되므로 그 기여도를 다음 사용자에게 붙이면 안 된다).
 *     계정 전환은 별 키(`user_<id>_<predictionId>` 를 담은 맵)로 구조적으로 격리되고, 로그아웃·탈퇴는
 *     [clearAll] 이 파일째 지운다.
 *
 *  값이 손상돼도(구버전 포맷 등) 예외를 화면까지 올리지 않고 해당 키를 폐기하고 빈 목록으로 폴백한다.
 *  무한 증가 방지로 사용자당 최신 [MAX_ENTRIES] 건만 남긴다 — 화면은 항상 '최신 예측' 하나만 읽으므로
 *  과거 항목은 재설치 없이 예측이 여러 번 생긴 경우의 여유분일 뿐이다.
 */
class SharedPrefsContributionCache(context: Context) : ContributionCache {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(predictionId: Int): List<ContributionItemDto> {
        if (predictionId <= 0) return emptyList()
        val userId = SessionStore.persistentUserId ?: return emptyList()
        return entries(userId)[predictionId.toString()].orEmpty()
    }

    override fun save(predictionId: Int, contributions: List<ContributionItemDto>) {
        // 빈 목록 = "이번 응답에 기여도가 없다"일 뿐 "기여도가 사라졌다"가 아니다(recalculated=false·구버전 서버).
        //   기존 캐시를 그대로 두는 것이 이 계약의 핵심이라, 지우는 경로 자체를 만들지 않는다.
        if (predictionId <= 0 || contributions.isEmpty()) return
        val userId = SessionStore.persistentUserId ?: return
        // 삽입 순서 유지(LinkedHashMap) — 상한 초과 시 오래된 것부터 버린다.
        val merged: MutableMap<String, List<ContributionItemDto>> = LinkedHashMap(entries(userId))
        merged.remove(predictionId.toString()) // 갱신은 맨 뒤로(최신으로 취급)
        merged[predictionId.toString()] = contributions
        val capped: Map<String, List<ContributionItemDto>> = if (merged.size > MAX_ENTRIES) {
            merged.entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
        } else {
            merged
        }
        runCatching {
            preferences.edit().putString(key(userId), json.encodeToString(capped)).apply()
        }.onFailure { Log.w(TAG, "기여도 캐시 저장 실패: ${it.message}") }
    }

    private fun entries(userId: Int): Map<String, List<ContributionItemDto>> = runCatching {
        val raw = preferences.getString(key(userId), null) ?: return emptyMap()
        json.decodeFromString<Map<String, List<ContributionItemDto>>>(raw)
    }.getOrElse {
        Log.w(TAG, "기여도 캐시 복원 실패 — 해당 키 폐기: ${it.message}")
        runCatching { preferences.edit().remove(key(userId)).apply() }
        emptyMap()
    }

    private fun key(userId: Int) = "user_${userId}_contributions"

    companion object {
        private const val PREFS = "contribution_cache"
        private const val TAG = "ContributionCache"
        private const val MAX_ENTRIES = 5
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 로그아웃·계정 전환·탈퇴 시 호출 — **사용자 구분 없이 파일째** 지운다.
         *
         *  구분해서 지우지 않는 이유: 로그아웃 경로는 [SessionStore.clearAuthentication] 로
         *  `persistentUserId` 가 null 이 되는 순간이 섞여 있어, "현재 사용자 것만" 지우면 호출 순서에 따라
         *  아무것도 안 지워지는 창이 생긴다. 건강 파생값을 기기에 남기지 않는 쪽이 언제나 맞다.
         */
        fun clearAll(context: Context) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }.onFailure { Log.w(TAG, "기여도 캐시 삭제 실패: ${it.message}") }
        }
    }
}
