package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsViewModel 단위 테스트 — GET/PATCH /users/me/settings 배선.
 *  로드 매핑 / PATCH 성공 시 응답 재동기화 / 실패 시 롤백 + saveError / 부분 변경 바디를 검증한다.
 *  suspend refresh()/saveSettings() 를 직접 호출해 Main 디스패처 없이 검증.
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

    @Test
    fun loads_settings_from_server() = runBlocking {
        val vm = SettingsViewModel(
            FakeSettingsApi(get = { UserSettingsResponse("large", "small", "cat", false) }),
        )
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
        assertEquals("medium", vm.fontSize) // 기본값 유지
        assertTrue(vm.loaded)
    }

    @Test
    fun save_success_syncs_state_from_response_and_sends_partial_body() = runBlocking {
        val api = FakeSettingsApi(
            get = { UserSettingsResponse() },
            patch = { UserSettingsResponse(fontSize = "large") }, // 서버 권위값
        )
        val vm = SettingsViewModel(api)

        val ok = vm.saveSettings(UserSettingsUpdateRequest(fontSize = "large")) { /* no rollback expected */ }

        assertTrue(ok)
        assertEquals("large", vm.fontSize)               // 응답으로 재동기화
        assertEquals("large", api.lastPatch?.fontSize)   // 변경 필드만 전송
        assertNull(api.lastPatch?.soundSize)             // 나머지는 null(부분 변경)
        assertNull(vm.saveError)
        assertFalse(vm.saving)
    }

    @Test
    fun save_failure_rolls_back_and_sets_error() = runBlocking {
        val api = FakeSettingsApi(
            get = { UserSettingsResponse(fontSize = "medium") },
            patch = { throw RuntimeException("boom") },
        )
        val vm = SettingsViewModel(api)
        vm.refresh() // fontSize = "medium"

        // 화면 로직처럼: 낙관적 적용 후 실패 → rollback 으로 이전값 복구
        var rolledBack = false
        val ok = vm.saveSettings(UserSettingsUpdateRequest(fontSize = "large")) { rolledBack = true }

        assertFalse(ok)
        assertTrue(rolledBack)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }
}
