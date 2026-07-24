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
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 로그아웃(#187). 앱 토큰뿐 아니라 **공급자(Google/Kakao) credential 도 해제**해, 한 폰 다인 시연에서
     * 다음 사람이 앞사람 계정으로 자동 로그인되지 않게 한다.
     *
     * 공급자 해제는 네트워크/콜백을 타므로 [viewModelScope](구성 변경·리라우팅에도 생존)에서 돌린다 —
     * 호출부의 컴포지션 스코프에서 실행하면 [onDone] 리라우팅으로 화면이 떠나는 순간 취소될 수 있다.
     * 공급자가 멈춰도 로그아웃은 완료돼야 하므로 3초로 상한을 두고, 성패와 무관하게 로컬 세션을 정리한 뒤
     * [onDone] 으로 라우팅 재평가를 호출부에 맡긴다.
     */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            withTimeoutOrNull(3_000) { SocialSignInClients.signOutProviders(getApplication()) }
            SessionStore.clearAuthentication(getApplication())
            onDone()
        }
    }
}
