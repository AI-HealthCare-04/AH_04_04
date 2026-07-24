package com.aihealthcare.ah0404.auth

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.aihealthcare.ah0404.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class ProviderCredential(val idToken: String, val nonce: String)

class SignInCancelledException : Exception()

object SocialSignInClients {
    private const val TAG = "SocialSignIn"

    val googleConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    val kakaoConfigured: Boolean get() = BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()

    /**
     * 로그아웃 시 공급자(Google/Kakao) credential 상태를 해제한다(#187).
     *  - Google: [CredentialManager.clearCredentialState] — 다음 로그인에서 계정 자동선택을 막는다.
     *  - Kakao: [UserApiClient.logout] — 카카오 세션 토큰만 폐기한다(연결 자체를 끊는 `unlink` 는 하지 않음).
     * 한 폰으로 여러 명이 번갈아 쓰는 시연에서 앞사람의 계정이 다음 사람에게 자동 선택되지 않게 하려는 것이다.
     * 어느 한쪽이 실패해도 앱 로그아웃은 이어져야 하므로 예외는 삼켜 로그만 남긴다(둘은 서로 독립적으로 시도).
     */
    suspend fun signOutProviders(context: Context) {
        if (googleConfigured) {
            runCatching {
                CredentialManager.create(context).clearCredentialState(
                    ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE),
                )
            }.onFailure { Log.w(TAG, "Google credential 해제 실패", it) }
        }
        if (kakaoConfigured) {
            suspendCancellableCoroutine { continuation ->
                UserApiClient.instance.logout { error ->
                    if (error != null) Log.w(TAG, "Kakao 로그아웃 실패", error)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    suspend fun google(activity: Activity): ProviderCredential {
        check(googleConfigured) { "Google OAuth client ID가 설정되지 않았습니다." }
        val nonce = secureNonce()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (_: GetCredentialCancellationException) {
            throw SignInCancelledException()
        }
        val credential = response.credential
        require(credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "지원하지 않는 Google 인증 응답입니다."
        }
        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        return ProviderCredential(googleCredential.idToken, nonce)
    }

    suspend fun kakao(activity: Activity): ProviderCredential {
        check(kakaoConfigured) { "Kakao Native App Key가 설정되지 않았습니다." }
        val nonce = secureNonce()
        return suspendCancellableCoroutine { continuation ->
            UserApiClient.instance.loginWithKakao(activity, nonce = nonce) { token, error ->
                if (!continuation.isActive) return@loginWithKakao
                when {
                    error is ClientError && error.reason == ClientErrorCause.Cancelled ->
                        continuation.resumeWithException(SignInCancelledException())
                    error != null -> continuation.resumeWithException(error)
                    token?.idToken.isNullOrBlank() -> continuation.resumeWithException(
                        IllegalStateException("Kakao OpenID Connect가 활성화되지 않았습니다."),
                    )
                    else -> continuation.resume(ProviderCredential(token.idToken!!, nonce))
                }
            }
        }
    }

    internal fun secureNonce(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
