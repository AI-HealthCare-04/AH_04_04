package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import com.aihealthcare.ah0404.network.UserUpdateRequest
import com.aihealthcare.ah0404.network.UserWithdrawRequest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 회원탈퇴(#356) VM 계약 — 결과를 세 갈래로 구분한다(리뷰 P1):
 *  - 성공: 정리 콜백(세션·공급자 credential 해제는 호출부 몫).
 *  - 명확한 서버 거절(4xx·5xx, 401 제외): 세션 유지 + 재시도 안내.
 *  - 결과 불명(타임아웃·연결 끊김·401): 서버가 이미 파기를 커밋했을 수 있으므로
 *    성공을 단정하지 않는 안내 후, 확인 시 정리 콜백(안전 측 로그아웃).
 * 진행 중 중복 요청 방지 포함.
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

    private class FakeUserApi(private val failure: Exception? = null) : UserApi {
        var calls = 0
        val requests = mutableListOf<UserWithdrawRequest>()
        override suspend fun getMe() = TODO()
        override suspend fun updateMe(body: UserUpdateRequest) = TODO()
        override suspend fun withdraw(body: UserWithdrawRequest) {
            calls++
            requests += body
            failure?.let { throw it }
        }
    }

    private fun httpError(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody()))

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
        assertNull(vm.withdrawNotice)
        assertFalse(vm.withdrawing)
    }

    @Test
    fun `명확한 서버 거절 - 정리 콜백 없이 안내 후 재시도 가능`() = runTest(dispatcher) {
        val userApi = FakeUserApi(failure = httpError(400))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0

        vm.withdraw(onWithdrawn = { cleaned++ })
        advanceUntilIdle()

        assertEquals(0, cleaned) // 서버가 거부를 응답했다 = 미처리 확실 → 세션 유지
        assertNotNull(vm.withdrawError)
        assertNull("거절은 결과 불명이 아니다", vm.withdrawNotice)
        assertFalse("실패 후 재시도할 수 있어야 한다", vm.withdrawing)

        vm.dismissWithdrawError()
        assertNull(vm.withdrawError)
    }

    @Test
    fun `결과 불명(IOException) - 성공 단정 없는 안내 후 확인 시 안전 로그아웃`() = runTest(dispatcher) {
        // 리뷰 P1 시나리오: 서버는 커밋했는데 응답만 유실. credential 을 유지하면
        // 다음 로그인에서 같은 계정 자동 선택 → 빈 신규 계정 즉시 생성.
        val userApi = FakeUserApi(failure = IOException("timeout"))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0

        vm.withdraw(onWithdrawn = { cleaned++ })
        advanceUntilIdle()

        assertEquals("안내 확인 전에는 정리하지 않는다", 0, cleaned)
        assertNull("불명은 재시도 오류로 안내하지 않는다", vm.withdrawError)
        assertEquals(SettingsViewModel.NOTICE_UNCERTAIN, vm.withdrawNotice)
        assertFalse(vm.withdrawing)

        vm.acknowledgeWithdrawNotice(onWithdrawn = { cleaned++ })
        assertEquals("확인 시 로그아웃과 같은 로컬 정리를 태운다", 1, cleaned)
        assertNull(vm.withdrawNotice)
    }

    @Test
    fun `결과 불명(401) - 이전 탈퇴가 이미 커밋된 재시도로 보고 안전 로그아웃 안내`() = runTest(dispatcher) {
        val userApi = FakeUserApi(failure = httpError(401))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0

        vm.withdraw(onWithdrawn = { cleaned++ })
        advanceUntilIdle()

        assertEquals(0, cleaned)
        assertNull(vm.withdrawError)
        assertEquals(SettingsViewModel.NOTICE_UNCERTAIN, vm.withdrawNotice)
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
