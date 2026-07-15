package com.aihealthcare.ah0404.exercise

import com.aihealthcare.ah0404.network.ExerciseVideoApi
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.network.ExerciseVideosResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ExerciseVideosViewModel 테스트 — GET /exercise-videos(#72) 로드/정렬/오류.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseVideosViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(val result: () -> ExerciseVideosResponse) : ExerciseVideoApi {
        override suspend fun getExerciseVideos() = result()
    }

    private fun item(stage: String, order: Int, available: Boolean = false, url: String? = null) =
        ExerciseVideoItem(stage, stage, order, url, null, available)

    @Test
    fun loads_and_sorts_by_order() = runTest {
        val vm = ExerciseVideosViewModel(
            FakeApi {
                ExerciseVideosResponse(
                    listOf(
                        item("cooldown", 4),
                        item("warmup", 1),
                        item("standing", 3, available = true, url = "https://v/s.mp4"),
                        item("seated", 2),
                    ),
                )
            },
        )
        vm.load(); advanceUntilIdle()

        assertEquals(listOf("warmup", "seated", "standing", "cooldown"), vm.videos.map { it.stage })
        assertTrue(vm.videos.first { it.stage == "standing" }.available)
        assertFalse(vm.videos.first { it.stage == "warmup" }.available)
        assertFalse(vm.error)
        assertTrue(vm.loaded)
    }

    @Test
    fun load_failure_sets_error() = runTest {
        val vm = ExerciseVideosViewModel(FakeApi { throw RuntimeException("boom") })
        vm.load(); advanceUntilIdle()
        assertTrue(vm.error)
        assertTrue(vm.videos.isEmpty())
        assertTrue(vm.loaded)
    }
}
