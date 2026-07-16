package com.aihealthcare.ah0404.exercise

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.ExerciseVideoApi
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 운동 영상(4단계) 상태 + 백엔드 배선(GET /exercise-videos, #72).
 *
 *  진입 시 1회 조회 후 캐시, order 순 정렬. available=false 단계는 화면에서 "준비중" 표시.
 *  진입마다 재조회 + generation 가드(겹친 조회 시 최신만 commit).
 */
class ExerciseVideosViewModel(
    private val api: ExerciseVideoApi = retrofit.create(ExerciseVideoApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var videos by mutableStateOf<List<ExerciseVideoItem>>(emptyList()); private set

    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val gen = ++generation
        loading = true
        error = false
        val result = try {
            Result.success(api.getExerciseVideos())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != generation) return
        result
            .onSuccess { videos = it.videos.sortedBy { v -> v.order } }
            .onFailure { error = true; Log.w(TAG, "운동 영상 조회 실패: ${it.message}") }
        loaded = true
        loading = false
    }

    companion object {
        const val TAG = "ExerciseVideos"
    }
}
