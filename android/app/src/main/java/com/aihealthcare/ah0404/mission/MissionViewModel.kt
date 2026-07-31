package com.aihealthcare.ah0404.mission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class MissionUiState {
    object Loading : MissionUiState()
    data class Success(
        val missions: List<Mission>,
        /** 단백질 미션 숨김 사유(#304 요청 4). null 이면 안내 카드 미표시. */
        val proteinHiddenNotice: String? = null,
    ) : MissionUiState()
    data class Error(val message: String) : MissionUiState()
}

class MissionViewModel(
    // 테스트 주입용 기본 인자 — 프로덕션은 기존과 동일하게 전역 retrofit 을 쓴다.
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
    private val profileApi: HealthProfileApi = retrofit.create(HealthProfileApi::class.java),
) : ViewModel() {

    private val _uiState = MutableStateFlow<MissionUiState>(MissionUiState.Loading)
    val uiState: StateFlow<MissionUiState> = _uiState

    init {
        loadMissions()
    }

    fun loadMissions() {
        viewModelScope.launch {
            _uiState.value = MissionUiState.Loading
            try {
                // 로그인 시 SessionStore 가 설정한 전역 토큰(TokenHolder)을 그대로 쓴다.
                //   예전에는 여기서 매번 guestLogin() 을 호출해 전역 토큰을 새 게스트로 덮어썼다 —
                //   그 결과 소셜 로그인 상태에서 미션 탭에 진입하는 것만으로 계정이 게스트로 갈렸다(#160).
                //   토큰이 없거나 만료면 getMissions() 가 401 을 받고, NetworkClient 의 전역 인터셉터가
                //   AuthFailureCoordinator 로 넘겨 로그인 화면으로 유도한다 — 화면이 스스로 로그인하지 않는다.
                val missionsResp = api.getMissions()
                _uiState.value = MissionUiState.Success(
                    missions = missionsResp.missions,
                    proteinHiddenNotice = proteinNoticeFor(missionsResp.missions),
                )
            } catch (e: Exception) {
                _uiState.value = MissionUiState.Error(e.message ?: "알 수 없는 오류")
            }
        }
    }

    /**
     * 단백질 미션 숨김 사유(#304 요청 4): 서버가 게이트(kidney·protein)로 meal 미션을 뺐을 때만
     * 최신 프로필을 조회해 사유를 만든다. 안내는 부가 기능 — 프로필 조회 실패는 조용히 null 로 삼켜
     * 미션 목록 표시를 막지 않는다.
     */
    private suspend fun proteinNoticeFor(missions: List<Mission>): String? {
        if (missions.any { it.missionType == "meal" }) return null
        return try {
            val profile = profileApi.getLatest()
            proteinHiddenReason(profile.kidneyStatus, profile.proteinRestrictionStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
