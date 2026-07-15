package com.aihealthcare.ah0404.profile

import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserInfoResponse
import com.aihealthcare.ah0404.network.UserUpdateRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProfileViewModel 단위 테스트 — GET/PATCH /users/me 배선 + 재진입 재조회 + 닉네임 저장 검증.
 *  suspend refresh()/saveNickname() 을 직접 호출해 Main 디스패처 없이 검증한다.
 */
class ProfileViewModelTest {

    private fun info(nickname: String = "홍길동", points: Int = 1250) = UserInfoResponse(
        userId = 1,
        provider = "guest",
        nickname = nickname,
        onboardingStatus = "completed",
        createdAt = "2026-07-01T09:00:00+09:00",
        birthDate = "1958-03-01",
        sex = "male",
        currentPoints = points,
        activityLevel = "normal",
    )

    private class FakeUserApi(
        var me: () -> UserInfoResponse,
        var patch: (UserUpdateRequest) -> UserInfoResponse = { throw IllegalStateException("not stubbed") },
    ) : UserApi {
        var meCalls = 0
        var lastPatch: UserUpdateRequest? = null
        override suspend fun getMe(): UserInfoResponse { meCalls++; return me() }
        override suspend fun updateMe(body: UserUpdateRequest): UserInfoResponse {
            lastPatch = body; return patch(body)
        }
    }

    @Test
    fun loads_user_info() = runBlocking {
        val vm = ProfileViewModel(FakeUserApi(me = { info() }))
        vm.refresh()
        assertNotNull(vm.info)
        assertEquals("홍길동", vm.info?.nickname)
        assertEquals(1250, vm.info?.currentPoints)
        assertFalse(vm.error)
        assertTrue(vm.loaded)
    }

    @Test
    fun reentry_refetches_latest() = runBlocking {
        val api = FakeUserApi(me = { info(nickname = "홍길동") })
        val vm = ProfileViewModel(api)
        vm.refresh()
        assertEquals("홍길동", vm.info?.nickname)

        api.me = { info(nickname = "김철수") }
        vm.refresh()
        assertEquals(2, api.meCalls)
        assertEquals("김철수", vm.info?.nickname)
    }

    @Test
    fun load_failure_sets_error() = runBlocking {
        val vm = ProfileViewModel(FakeUserApi(me = { throw RuntimeException("boom") }))
        vm.refresh()
        assertTrue(vm.error)
        assertNull(vm.info)
        assertTrue(vm.loaded)
    }

    @Test
    fun save_nickname_success_updates_info() = runBlocking {
        val api = FakeUserApi(
            me = { info(nickname = "홍길동") },
            patch = { info(nickname = it.nickname) },
        )
        val vm = ProfileViewModel(api)
        vm.refresh()

        val ok = vm.saveNickname("  새이름  ") // 앞뒤 공백은 trim 된다.
        assertTrue(ok)
        assertEquals("새이름", vm.info?.nickname)
        assertEquals("새이름", api.lastPatch?.nickname)
        assertNull(vm.saveError)
        assertFalse(vm.saving)
    }

    @Test
    fun save_nickname_rejects_blank_without_calling_api() = runBlocking {
        val api = FakeUserApi(me = { info() })
        val vm = ProfileViewModel(api)

        val ok = vm.saveNickname("   ")
        assertFalse(ok)
        assertNotNull(vm.saveError)
        assertNull(api.lastPatch) // API 호출 안 함
    }

    @Test
    fun save_nickname_failure_sets_save_error() = runBlocking {
        val api = FakeUserApi(me = { info() }, patch = { throw RuntimeException("boom") })
        val vm = ProfileViewModel(api)

        val ok = vm.saveNickname("새이름")
        assertFalse(ok)
        assertNotNull(vm.saveError)
        assertFalse(vm.saving)
    }
}
