package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SettingsViewModel 단위 테스트 — GET/PATCH /users/me/settings(리뷰 #71·#73 수렴 정책).
 *  전면 직렬화 + 큐 비면 서버 수렴이 모든 경쟁/실패 조합에서 화면=서버 로 수렴함을 공개 API 로 검증.
 *  StandardTestDispatcher 로 viewModelScope(Main) 를 제어하고, gate 로 진행 순서를 고정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** 서버 상태를 들고 부분 PATCH 를 반영해 전체를 돌려주는 fake. 첫 PATCH 를 gate 로 지연할 수 있다. */
    private class ServerFake(
        private val firstPatchGate: CompletableDeferred<Unit>? = null,
        private val failPatch: Boolean = false,
    ) : SettingsApi {
        var font = "medium"; var sound = "medium"; var pet = "default"; var music = true
        var getCalls = 0; var patchCalls = 0
        override suspend fun getSettings(): UserSettingsResponse {
            getCalls++
            return UserSettingsResponse(font, sound, pet, music)
        }
        override suspend fun updateSettings(body: UserSettingsUpdateRequest): UserSettingsResponse {
            patchCalls++
            if (patchCalls == 1) firstPatchGate?.await()
            if (failPatch) throw RuntimeException("boom")
            body.fontSize?.let { font = it }
            body.soundSize?.let { sound = it }
            body.petType?.let { pet = it }
            body.musicEnabled?.let { music = it }
            return UserSettingsResponse(font, sound, pet, music)
        }
    }

    private class SimpleFake(
        var get: () -> UserSettingsResponse,
    ) : SettingsApi {
        override suspend fun getSettings() = get()
        override suspend fun updateSettings(body: UserSettingsUpdateRequest) = get()
    }

    /** 초기엔 정상, offline=true 이후 GET/PATCH 모두 실패(네트워크 단절 재현). */
    private class FlakyFake : SettingsApi {
        var offline = false
        var font = "medium"; var sound = "medium"; var pet = "default"; var music = true
        override suspend fun getSettings(): UserSettingsResponse {
            if (offline) throw RuntimeException("offline")
            return UserSettingsResponse(font, sound, pet, music)
        }
        override suspend fun updateSettings(body: UserSettingsUpdateRequest): UserSettingsResponse {
            if (offline) throw RuntimeException("offline")
            body.fontSize?.let { font = it }; body.soundSize?.let { sound = it }
            body.petType?.let { pet = it }; body.musicEnabled?.let { music = it }
            return UserSettingsResponse(font, sound, pet, music)
        }
    }

    @Test
    fun loads_settings_from_server() = runTest {
        val vm = SettingsViewModel(SimpleFake(get = { UserSettingsResponse("large", "small", "cat", false) }))
        vm.load(); advanceUntilIdle()
        assertEquals("large", vm.fontSize)
        assertEquals("small", vm.soundSize)
        assertEquals("cat", vm.petType)
        assertFalse(vm.musicEnabled)
        assertFalse(vm.loadError)
        assertTrue(vm.loaded)
    }

    @Test
    fun load_failure_sets_error() = runTest {
        val vm = SettingsViewModel(SimpleFake(get = { throw RuntimeException("boom") }))
        vm.load(); advanceUntilIdle()
        assertTrue(vm.loadError)
        assertTrue(vm.loaded)
    }

    @Test
    fun default_pet_type_is_normalized_to_dog() = runTest {
        val vm = SettingsViewModel(SimpleFake(get = { UserSettingsResponse(petType = "default") }))
        vm.load(); advanceUntilIdle()
        assertEquals("dog", vm.petType)
    }

    @Test
    fun change_syncs_to_server() = runTest {
        val api = ServerFake()
        val vm = SettingsViewModel(api)
        vm.changeFontSize("large"); advanceUntilIdle()
        assertEquals("large", vm.fontSize)
        assertEquals("large", api.font) // 서버 반영
        assertFalse(vm.saving)
    }

    @Test
    fun save_failure_converges_to_server_and_shows_error() = runTest {
        // PATCH 실패, GET 은 서버 medium 반환 → 낙관적 large 가 medium 으로 수렴.
        val api = ServerFake(failPatch = true)
        val vm = SettingsViewModel(api)
        vm.changeFontSize("large"); advanceUntilIdle()
        assertEquals("medium", vm.fontSize) // 서버 권위값으로 수렴(낙관적 large 잔류 안 함)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }

    /** 리뷰 #73-1: 저장 큐 도중 재조회가 껴도, 최종적으로 성공한 PATCH 결과(서버)로 수렴한다. */
    @Test
    fun reentry_get_during_save_queue_converges_to_saved_values() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = ServerFake(firstPatchGate = gate)
        val vm = SettingsViewModel(api)

        vm.changeFontSize("large"); runCurrent()  // 첫 PATCH 진입(gate 대기, 큐 점유)
        vm.changeSoundSize("large"); runCurrent()  // 둘째 PATCH 큐잉
        vm.load(); runCurrent()                    // 재조회 큐잉(직렬화되어 저장 뒤로)

        gate.complete(Unit); advanceUntilIdle()

        assertEquals("large", vm.fontSize)
        assertEquals("large", vm.soundSize)
        assertEquals("large", api.font)
        assertEquals("large", api.sound)
    }

    /** 리뷰 #73(재검): PATCH 와 수렴 GET 이 모두 실패(오프라인)해도, 마지막 서버 확인값으로 복구된다. */
    @Test
    fun offline_change_reverts_to_last_confirmed_value() = runTest {
        val api = FlakyFake()
        val vm = SettingsViewModel(api)
        vm.load(); advanceUntilIdle()          // confirmed = medium
        assertEquals("medium", vm.fontSize)

        api.offline = true                      // 네트워크 단절
        vm.changeFontSize("large"); advanceUntilIdle() // 낙관 large → PATCH 실패 + 수렴 GET 실패

        assertEquals("medium", vm.fontSize)     // 미저장 large 잔류 안 함(마지막 확인값 복구)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }

    /** 리뷰 #73-2: 직렬 저장 두 건이 모두 실패해도, 큐가 비면 서버값으로 수렴해 두 필드 모두 복구된다. */
    @Test
    fun both_saves_failing_converge_all_fields_to_server() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = ServerFake(firstPatchGate = gate, failPatch = true)
        val vm = SettingsViewModel(api)

        vm.changeFontSize("large"); runCurrent()  // 낙관적 large, 첫 PATCH gate 대기(실패 예정)
        vm.changeSoundSize("large"); runCurrent()  // 낙관적 large, 둘째 PATCH 큐잉(실패 예정)

        gate.complete(Unit); advanceUntilIdle()

        assertEquals("medium", vm.fontSize) // 첫 낙관적 변경도 복구
        assertEquals("medium", vm.soundSize)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }

    /** 리뷰 #86 후속: 최초 GET 실패(loadError=true) 후 저장이 성공하면 loadError 가 해제되어야
     *  전역 적용이 서버 확인값을 반영할 수 있다. */
    @Test
    fun successful_save_after_load_failure_clears_load_error() = runTest {
        val api = object : SettingsApi {
            var getCalls = 0
            override suspend fun getSettings(): UserSettingsResponse {
                getCalls++
                if (getCalls == 1) throw RuntimeException("offline") // 최초 조회 실패
                return UserSettingsResponse("small", "small", "dog", true)
            }
            override suspend fun updateSettings(body: UserSettingsUpdateRequest) =
                UserSettingsResponse(body.fontSize ?: "small", "small", "dog", true)
        }
        val vm = SettingsViewModel(api)

        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.loadError) // 최초 GET 실패

        vm.changeFontSize("small"); advanceUntilIdle() // PATCH 성공 → applyResponse
        assertFalse(vm.loadError) // 서버 확인값 들어와 loadError 해제
        assertEquals("small", vm.fontSize)
    }
}
