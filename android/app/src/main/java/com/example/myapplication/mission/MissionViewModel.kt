package com.example.myapplication.mission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.network.LoginRequest
import com.example.myapplication.network.Mission
import com.example.myapplication.network.MissionApi
import com.example.myapplication.network.TokenHolder
import com.example.myapplication.network.retrofit
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
                val loginResp = api.login(LoginRequest("test-user-1"))
                TokenHolder.token = loginResp.accessToken
                val missionsResp = api.getMissions()
                _uiState.value = MissionUiState.Success(missionsResp.missions)
            } catch (e: Exception) {
                _uiState.value = MissionUiState.Error(e.message ?: "알 수 없는 오류")
            }
        }
    }
}
