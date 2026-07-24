package com.aihealthcare.ah0404.network

import android.content.Context

/** 현재 로그인 세션(#153). 영속화 정책을 한곳에서 강제하기 위한 값 객체. */
data class AuthSession(
    val accessToken: String,
    val isGuest: Boolean,
    val onboardingCompleted: Boolean,
    val userId: Int? = null,
)

/**
 * 로컬 세션 영속화 — 리뷰 #63(지영 P1-2), 재설계 #153.
 *
 *  핵심 원칙(#153): **서버 = 진실, 로컬 = 로그인마다 서버값으로 갱신되는 캐시.**
 *   - 디스크(SharedPreferences)에는 **소셜(비게스트) + 온보딩 완료** 세션만 남긴다.
 *   - **게스트는 토큰·완료 플래그 어느 것도 디스크에 쓰지 않는다** → 한 폰 다인 시연에서
 *     이전 사람의 세션이 남지 않는다(#153 증상 ②).
 *   - **미완료 소셜**(약관 동의 전 등)도 디스크에 안 쓴다 → 약관 화면에서 종료해도 잔존하지 않는다(증상 ①).
 *
 *  라우팅 게이트는 디스크가 아니라 메모리 [sessionOnboarded] 다: 시작 시 디스크값으로 초기화되고,
 *  로그인/온보딩 완료로 갱신된다. 그래서 게스트가 이번 세션에 온보딩을 마치면 MAIN 으로 가되(메모리 true)
 *  디스크엔 아무것도 안 남아 재실행 시 시작화면으로 복귀한다.
 *
 *  MVP 는 전역 boolean(KEY_ONBOARDED)을 유지한다. 이는 "진실 원천"이 아니라 **현재 저장된 토큰에
 *  딸린 서버 상태의 캐시**이며, 로그인마다 서버값으로 덮어써진다. 계정별 PersistedSession 전환은 #105 후속.
 */
object SessionStore {
    private const val PREFS = "aigo_session"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_ONBOARDED = "onboarding_completed"
    private const val KEY_USER_ID = "user_id"

    /**
     * 라우팅 게이트(#153): "이번 세션이 온보딩을 마쳤는가". 시작 시 [restore] 로 디스크값을 실어오고,
     * [applyLogin]/[markOnboarded] 로 갱신된다. 게스트 완주는 여기(메모리)만 true 이고 디스크엔 안 남는다.
     * (Compose 라우팅은 값 변경 뒤 sessionRevision 을 증가시켜 재평가한다 — 별도 관찰 불필요.)
     */
    @Volatile
    var sessionOnboarded: Boolean = false
        private set

    /**
     * 사용자별 경량 로컬 상태(#145)의 키로 쓸 **완료된 소셜 계정의 서버 user_id**.
     * 게스트·미완료 세션·로그아웃 상태에서는 null이라 로컬 상태를 영속화하지 않는다.
     */
    @Volatile
    var persistentUserId: Int? = null
        private set

    private var currentUserId: Int? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 앱 시작 시: 저장된 토큰 + 완료 플래그를 메모리로 복원. */
    fun restore(context: Context) {
        val p = prefs(context)
        val token = p.getString(KEY_TOKEN, null).orEmpty()
        val onboarded = p.getBoolean(KEY_ONBOARDED, false)
        val storedUserId = runCatching {
            p.getInt(KEY_USER_ID, -1).takeIf { it > 0 }
        }.getOrElse {
            p.edit().remove(KEY_USER_ID).apply()
            null
        }
        // #145 마이그레이션: #153 이전 설치에는 user_id 키가 없으므로, 이미 서버 로그인으로 받은
        // JWT의 안정적인 user_id claim을 한 번 읽어 새 키로 옮긴다. 토큰 원문 자체를 저장 키로 쓰지는 않는다.
        val restoredUserId = if (token.isNotBlank() && onboarded) {
            storedUserId ?: JwtTokenInspector.userId(token)
        } else {
            null
        }
        TokenHolder.token = token
        sessionOnboarded = onboarded
        currentUserId = restoredUserId
        persistentUserId = restoredUserId
        if (restoredUserId != null && storedUserId == null) {
            p.edit().putInt(KEY_USER_ID, restoredUserId).apply()
        }
    }

    /** 디스크 캐시 값(계정 전환 검증·테스트용). 라우팅은 [sessionOnboarded] 를 쓴다. */
    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    /**
     * 로그인 결과 반영(#153). 토큰은 **항상 메모리**에 둬 이번 세션이 동작하게 하고,
     * 디스크 영속화·라우팅 게이트는 **소셜(비게스트) + 온보딩 완료**일 때만 true 로 둔다.
     * 그 외(게스트·미완료)는 디스크 잔재(토큰·플래그)를 지워 계정 전환 시 이전 세션이 새지 않게 한다.
     */
    fun applyLogin(context: Context, session: AuthSession) {
        TokenHolder.token = session.accessToken
        currentUserId = session.userId?.takeIf { it > 0 }
        val eligible = !session.isGuest && session.onboardingCompleted
        sessionOnboarded = eligible
        persistentUserId = currentUserId.takeIf { eligible }
        val editor = prefs(context).edit()
        if (eligible) {
            editor.putString(KEY_TOKEN, session.accessToken).putBoolean(KEY_ONBOARDED, true)
            persistentUserId?.let { editor.putInt(KEY_USER_ID, it) } ?: editor.remove(KEY_USER_ID)
        } else {
            editor.remove(KEY_TOKEN).remove(KEY_USER_ID).putBoolean(KEY_ONBOARDED, false)
        }
        editor.apply()
        AuthFailureCoordinator.onAuthenticated()
    }

    /**
     * 온보딩 완료 확정 시(결과 화면 → 홈). 이번 세션은 항상 MAIN 으로 가고(메모리 게이트 true),
     * **디스크 영속화는 소셜만** — 게스트는 토큰·플래그 어느 것도 남기지 않는다(#153).
     */
    fun markOnboarded(context: Context, isGuest: Boolean) {
        sessionOnboarded = true
        if (!isGuest) {
            persistentUserId = currentUserId
            val editor = prefs(context).edit()
                .putString(KEY_TOKEN, TokenHolder.token)
                .putBoolean(KEY_ONBOARDED, true)
            persistentUserId?.let { editor.putInt(KEY_USER_ID, it) } ?: editor.remove(KEY_USER_ID)
            editor.apply()
        } else {
            persistentUserId = null
        }
        AuthFailureCoordinator.onAuthenticated()
    }

    /**
     * 로그아웃 시 인증 정보만 제거한다(#154). 온보딩 완료 여부는 계정 인증과 분리해 보존하고
     * [sessionOnboarded] 게이트도 유지 → 라우팅은 LOGIN_REQUIRED 로 가 같은 계정 재로그인 시 온보딩을 반복하지 않는다.
     * (로그아웃은 완료된 소셜 계정에서만 도달하므로 완료 플래그 보존이 안전하다.)
     */
    fun clearAuthentication(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_USER_ID).apply()
        TokenHolder.token = ""
        currentUserId = null
        persistentUserId = null
    }

    /**
     * [DEBUG 탈출구] 세션 전체 초기화(토큰 + 완료 플래그 + 메모리 게이트) → 다음 라우팅이 온보딩(시작화면)으로 복귀(#119).
     * 키 미설정 개발 빌드에서 LOGIN_REQUIRED에 갇힌 테스터의 탈출용. 운영 노출 금지(호출부는 DEBUG 전용).
     */
    fun resetSession(context: Context) {
        prefs(context).edit().clear().apply()
        TokenHolder.token = ""
        sessionOnboarded = false
        currentUserId = null
        persistentUserId = null
        AuthFailureCoordinator.onAuthenticated()
    }
}
