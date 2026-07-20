package com.aihealthcare.ah0404.network

import android.content.Context

/**
 * 로컬 세션 영속화 — 리뷰 #63(지영 P1-2) 반영.
 *
 *  정식 온보딩 완료 상태와 액세스 토큰을 앱 재시작/Activity 재생성 후에도 보존한다.
 *  - 시작 시 restore() 로 토큰을 TokenHolder 에 복원.
 *  - 온보딩 완료 확정(결과 화면 → 홈) 시 markOnboarded() 로 토큰 + 완료 플래그 저장.
 *  - 로그아웃 시 토큰만 삭제하고 완료 플래그는 보존한다(#88, #94).
 *  - 체험하기는 이 저장소를 호출하지 않아 재실행 시 시작 화면으로 복귀한다.
 *
 *  MVP 는 SharedPreferences 사용. 토큰 보안 강화가 필요하면 EncryptedSharedPreferences/
 *  DataStore 로 교체한다(// TODO). 서버 상태(/home)로의 완료 재검증도 후속 개선.
 */
object SessionStore {
    private const val PREFS = "aigo_session"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_ONBOARDED = "onboarding_completed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 앱 시작 시: 저장된 토큰을 TokenHolder 로 복원. */
    fun restore(context: Context) {
        TokenHolder.token = prefs(context).getString(KEY_TOKEN, null).orEmpty()
    }

    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    /** 온보딩 완료 확정 시: 현재 토큰 + 완료 플래그 저장. */
    fun markOnboarded(context: Context) {
        prefs(context).edit()
            .putString(KEY_TOKEN, TokenHolder.token)
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }

    /** 로그아웃 시 인증 정보만 제거한다. 온보딩 완료 여부는 계정 인증과 분리해 보존한다. */
    fun clearAuthentication(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
        TokenHolder.token = ""
    }
}
