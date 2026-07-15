package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsViewModel 단위 테스트 — GET/PATCH /users/me/settings 배선(리뷰 #71 반영).
 *  로드 매핑 / PATCH 성공 재동기화·부분바디 / 실패 롤백 / GET-저장 경쟁 / 저장 직렬화 / pet 기본값 정규화.
 *  suspend refresh()/commitSave() 를 직접 호출해 Main 디스패처 없이 검증.
 */
class SettingsViewModelTest {

    private class FakeSettingsApi(
        var get: () -> UserSettingsResponse,
        var patch: (UserSettingsUpdateRequest) -> UserSettingsResponse = { throw IllegalStateException("stub") },
    ) : SettingsApi {
        var lastPatch: UserSettingsUpdateRequest? = null
        override suspend fun getSettings(): UserSettingsResponse = get()
        override suspend fun updateSettings(body: UserSettingsUpdateRequest): UserSettingsResponse {
            lastPatch = body; return patch(body)
        }
    }

    /** 서버 상태를 들고 부분 PATCH 를 반영해 전체를 돌려주는 fake(직렬화·경쟁 검증용). */
    private class ServerFake(
        private val getGate: CompletableDeferred<Unit>? = null,
        private val firstPatchGate: CompletableDeferred<Unit>? = null,
    ) : SettingsApi {
        var font = "medium"; var sound = "medium"; var pet = "default"; var music = true
        var getCalls = 0; var patchCalls = 0
        override suspend fun getSettings(): UserSettingsResponse {
            getCalls++
            if (getCalls >= 2) getGate?.await() // 두 번째 GET(느린 재진입)만 대기
            return UserSettingsResponse(font, sound, pet, music)
        }
        override suspend fun updateSettings(body: UserSettingsUpdateRequest): UserSettingsResponse {
            patchCalls++
            if (patchCalls == 1) firstPatchGate?.await() // 첫 PATCH 지연
            body.fontSize?.let { font = it }
            body.soundSize?.let { sound = it }
            body.petType?.let { pet = it }
            body.musicEnabled?.let { music = it }
            return UserSettingsResponse(font, sound, pet, music)
        }
    }

    @Test
    fun loads_settings_from_server() = runBlocking {
        val vm = SettingsViewModel(FakeSettingsApi(get = { UserSettingsResponse("large", "small", "cat", false) }))
        vm.refresh()
        assertEquals("large", vm.fontSize)
        assertEquals("small", vm.soundSize)
        assertEquals("cat", vm.petType)
        assertFalse(vm.musicEnabled)
        assertFalse(vm.loadError)
        assertTrue(vm.loaded)
    }

    @Test
    fun load_failure_sets_error_and_keeps_defaults() = runBlocking {
        val vm = SettingsViewModel(FakeSettingsApi(get = { throw RuntimeException("boom") }))
        vm.refresh()
        assertTrue(vm.loadError)
        assertEquals("medium", vm.fontSize)
        assertTrue(vm.loaded)
    }

    @Test
    fun save_success_syncs_state_and_sends_partial_body() = runBlocking {
        val api = FakeSettingsApi(
            get = { UserSettingsResponse() },
            patch = { UserSettingsResponse(fontSize = "large") },
        )
        val vm = SettingsViewModel(api)

        val ok = vm.commitSave(UserSettingsUpdateRequest(fontSize = "large")) { /* no rollback */ }

        assertTrue(ok)
        assertEquals("large", vm.fontSize)
        assertEquals("large", api.lastPatch?.fontSize)
        assertNull(api.lastPatch?.soundSize) // 변경 필드만 전송(부분)
        assertNull(vm.saveError)
        assertFalse(vm.saving)
    }

    @Test
    fun save_failure_rolls_back_and_sets_error() = runBlocking {
        val api = FakeSettingsApi(get = { UserSettingsResponse() }, patch = { throw RuntimeException("boom") })
        val vm = SettingsViewModel(api)

        var rolledBack = false
        val ok = vm.commitSave(UserSettingsUpdateRequest(fontSize = "large")) { rolledBack = true }

        assertFalse(ok)
        assertTrue(rolledBack)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }

    /** 리뷰 #71-1: 진입(느린 GET) 도중 저장이 성공하면, 늦은 GET 이 저장값을 이전 값으로 덮지 않는다. */
    @Test
    fun save_wins_over_late_reentry_get() = runBlocking {
        val getGate = CompletableDeferred<Unit>()
        val api = ServerFake(getGate = getGate)
        val vm = SettingsViewModel(api)

        vm.refresh() // 1차 로드: medium
        assertEquals("medium", vm.fontSize)

        val reentry = launch { vm.refresh() } // 2차(느린) GET → gate 대기
        while (api.getCalls < 2) yield()

        val ok = vm.commitSave(UserSettingsUpdateRequest(fontSize = "large")) { }
        assertTrue(ok)
        assertEquals("large", vm.fontSize)

        getGate.complete(Unit) // 느린 GET 완료(이전 medium) → 낡은 version 이라 반영 안 됨
        reentry.join()

        assertEquals("large", vm.fontSize)
    }

    /** 리뷰 #71-2: 느린 첫 PATCH + 빠른 두 번째 PATCH 가 겹쳐도 직렬화로 최신 값이 유지된다. */
    @Test
    fun serialized_saves_keep_latest_values() = runBlocking {
        val patchGate = CompletableDeferred<Unit>()
        val api = ServerFake(firstPatchGate = patchGate)
        val vm = SettingsViewModel(api)

        val s1 = launch { vm.commitSave(UserSettingsUpdateRequest(fontSize = "large")) { } }
        while (api.patchCalls < 1) yield() // s1 이 첫 PATCH 진입(뮤텍스 점유 + gate 대기)

        val s2 = launch { vm.commitSave(UserSettingsUpdateRequest(soundSize = "large")) { } }
        yield() // s2 시작(version 증가, 뮤텍스 대기)

        patchGate.complete(Unit) // s1 PATCH 완료 → 낡은 version 이라 재동기화 skip, 뮤텍스 해제 → s2 진행
        s1.join(); s2.join()

        assertEquals("large", vm.fontSize)  // s1 변경 보존
        assertEquals("large", vm.soundSize) // s2 최신값 보존(이전 medium 으로 안 돌아감)
        assertFalse(vm.saving)
    }

    /** 리뷰 #71-3: 서버 기본 pet_type="default"는 세그먼트가 표현 못 하므로 "dog"로 정규화한다. */
    @Test
    fun default_pet_type_is_normalized_to_dog() = runBlocking {
        val vm = SettingsViewModel(FakeSettingsApi(get = { UserSettingsResponse(petType = "default") }))
        vm.refresh()
        assertEquals("dog", vm.petType)
    }
}
