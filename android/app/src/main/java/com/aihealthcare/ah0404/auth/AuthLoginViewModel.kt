package com.aihealthcare.ah0404.auth

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.AuthSession
import com.aihealthcare.ah0404.network.OnbEnums
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.retrofit
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class SocialProvider { GOOGLE, KAKAO }

data class AuthLoginUiState(
    val loading: SocialProvider? = null,
    val message: String? = null,
)

class AuthLoginViewModel(application: Application) : AndroidViewModel(application) {
    private val api = retrofit.create(OnboardingApi::class.java)
    private val mutableState = MutableStateFlow(AuthLoginUiState())
    val state: StateFlow<AuthLoginUiState> = mutableState.asStateFlow()

    /**
     * 소셜 로그인. 성공 시 [onResult] 로 **온보딩 완료 여부**를 넘겨, 호출부가 라우팅을 분기한다(#153).
     *   완료 → 홈으로(약관 건너뜀), 미완료 → 온보딩(약관)을 이어감.
     */
    fun signIn(provider: SocialProvider, activity: Activity, onResult: (completed: Boolean) -> Unit) {
        if (mutableState.value.loading != null) return
        viewModelScope.launch {
            mutableState.value = AuthLoginUiState(loading = provider)
            try {
                val credential = when (provider) {
                    SocialProvider.GOOGLE -> SocialSignInClients.google(activity)
                    SocialProvider.KAKAO -> SocialSignInClients.kakao(activity)
                }
                val request = SocialLoginRequest(credential.idToken, credential.nonce)
                val response = when (provider) {
                    SocialProvider.GOOGLE -> api.loginGoogle(request)
                    SocialProvider.KAKAO -> api.loginKakao(request)
                }
                val user = response.user
                val completed = user.onboardingStatus == OnbEnums.COMPLETED
                // 영속화 정책은 SessionStore 한곳에서 강제한다(#153): 게스트·미완료는 메모리만, 완료 소셜만 디스크.
                SessionStore.applyLogin(
                    getApplication(),
                    AuthSession(response.accessToken, user.isGuest, completed, user.userId),
                )
                mutableState.value = AuthLoginUiState()
                onResult(completed)
            } catch (_: SignInCancelledException) {
                mutableState.value = AuthLoginUiState(message = "로그인을 취소했어요.")
            } catch (_: IOException) {
                mutableState.value = AuthLoginUiState(message = "인터넷 연결을 확인하고 다시 시도해 주세요.")
            } catch (exception: HttpException) {
                mutableState.value = AuthLoginUiState(
                    message = if (exception.code() in 500..599) {
                        "로그인 서버가 잠시 불안정해요. 잠시 후 다시 시도해 주세요."
                    } else {
                        "인증 정보를 확인하지 못했어요. 다시 로그인해 주세요."
                    },
                )
            } catch (_: Exception) {
                mutableState.value = AuthLoginUiState(message = "로그인에 실패했어요. 다시 시도해 주세요.")
            }
        }
    }
}
