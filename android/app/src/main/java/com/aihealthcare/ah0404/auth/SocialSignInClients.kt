package com.aihealthcare.ah0404.auth

import android.app.Activity
import android.util.Base64
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
    val googleConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    val kakaoConfigured: Boolean get() = BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()

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
