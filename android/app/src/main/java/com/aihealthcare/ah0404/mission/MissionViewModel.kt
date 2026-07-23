package com.aihealthcare.ah0404.mission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class MissionUiState {
    object Loading : MissionUiState()
    data class Success(val missions: List<Mission>) : MissionUiState()
    data class Error(val message: String) : MissionUiState()
}

class MissionViewModel : ViewModel() {
    private val api = retrofit.create(MissionApi::class.java)

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
                _uiState.value = MissionUiState.Success(missionsResp.missions)
            } catch (e: Exception) {
                _uiState.value = MissionUiState.Error(e.message ?: "알 수 없는 오류")
            }
        }
    }
}
