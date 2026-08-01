package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import com.aihealthcare.ah0404.network.UserUpdateRequest
import com.aihealthcare.ah0404.network.UserWithdrawRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 회원탈퇴(#356) VM 계약: 성공 시에만 정리 콜백을 부르고(세션·공급자 credential 해제는 호출부 몫),
 * 실패는 안내 후 재시도 가능 상태로 남으며, 진행 중 중복 요청을 막는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWithdrawTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class StubSettingsApi : SettingsApi {
        override suspend fun getSettings() = UserSettingsResponse("medium", "medium", "dog", true)
        override suspend fun updateSettings(body: UserSettingsUpdateRequest) =
            UserSettingsResponse("medium", "medium", "dog", true)
    }

    private class FakeUserApi(private val fail: Boolean = false) : UserApi {
        var calls = 0
        val requests = mutableListOf<UserWithdrawRequest>()
        override suspend fun getMe() = TODO()
        override suspend fun updateMe(body: UserUpdateRequest) = TODO()
        override suspend fun withdraw(body: UserWithdrawRequest) {
            calls++
            requests += body
            if (fail) throw RuntimeException("network down")
        }
    }

    @Test
    fun `탈퇴 성공 - confirm=true 로 보내고 정리 콜백을 부른다`() = runTest(dispatcher) {
        val userApi = FakeUserApi()
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0

        vm.withdraw(onWithdrawn = { cleaned++ })
        advanceUntilIdle()

        assertEquals(1, userApi.calls)
        assertTrue("서버가 confirm=true 를 강제한다", userApi.requests.single().confirm)
        assertEquals(1, cleaned)
        assertNull(vm.withdrawError)
        assertFalse(vm.withdrawing)
    }

    @Test
    fun `탈퇴 실패 - 정리 콜백을 부르지 않고 안내 후 재시도 가능`() = runTest(dispatcher) {
        val userApi = FakeUserApi(fail = true)
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0

        vm.withdraw(onWithdrawn = { cleaned++ })
        advanceUntilIdle()

        assertEquals(0, cleaned) // 실패했는데 로그아웃시키면 상태만 꼬인다
        assertNotNull(vm.withdrawError)
        assertFalse("실패 후 재시도할 수 있어야 한다", vm.withdrawing)

        vm.dismissWithdrawError()
        assertNull(vm.withdrawError)
    }

    @Test
    fun `진행 중 중복 요청은 무시된다`() = runTest(dispatcher) {
        val userApi = FakeUserApi()
        val vm = SettingsViewModel(StubSettingsApi(), userApi)

        vm.withdraw(onWithdrawn = {})
        vm.withdraw(onWithdrawn = {}) // 첫 요청이 아직 in-flight
        advanceUntilIdle()

        assertEquals(1, userApi.calls)
    }
}
