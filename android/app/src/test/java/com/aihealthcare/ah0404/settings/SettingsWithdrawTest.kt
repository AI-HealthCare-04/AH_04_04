package com.aihealthcare.ah0404.settings

import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import com.aihealthcare.ah0404.network.UserUpdateRequest
import com.aihealthcare.ah0404.network.UserWithdrawRequest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * 회원탈퇴(#356) VM 계약 — 결과 3분기(리뷰 P1 1·2차):
 *  - 성공: [onWithdrawn](세션·공급자 credential 해제는 호출부 몫).
 *  - 명확한 서버 거절(**401 제외 4xx 만** — 미처리가 계약상 확실): 세션 유지 + 재시도 안내.
 *  - 결과 불명(타임아웃·연결 끊김, 401, **5xx 전부**): 서버가 커밋했는지 알 수 없으므로
 *    [onUncertain] 이 **catch 에서 즉시** 호출된다 — 다이얼로그 확인을 기다리지 않는다
 *    (전역 라우팅이 VM 을 먼저 폐기해도 Activity 범위 정리가 이미 시작돼 있어야 한다).
 *  - 요청 in-flight 중 VM 폐기로 취소돼도(리뷰 3차 P1: 전역 라우팅이 catch 보다 먼저
 *    ViewModelStore 를 clear) [onUncertain] 은 시작되고 취소는 재전파된다.
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

    private class FakeUserApi(
        private val failure: Exception? = null,
        // true 면 응답 대기 상태로 매달린다(in-flight 중 scope 취소 경합 재현용).
        private val hang: Boolean = false,
    ) : UserApi {
        var calls = 0
        val requests = mutableListOf<UserWithdrawRequest>()
        override suspend fun getMe() = TODO()
        override suspend fun updateMe(body: UserUpdateRequest) = TODO()
        override suspend fun withdraw(body: UserWithdrawRequest) {
            calls++
            requests += body
            if (hang) awaitCancellation()
            failure?.let { throw it }
        }
    }

    private fun httpError(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody()))

    @Test
    fun `탈퇴 성공 - confirm=true 로 보내고 정리 콜백을 부른다`() = runTest(dispatcher) {
        val userApi = FakeUserApi()
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0
        var uncertain = 0

        vm.withdraw(onWithdrawn = { cleaned++ }, onUncertain = { uncertain++ })
        advanceUntilIdle()

        assertEquals(1, userApi.calls)
        assertTrue("서버가 confirm=true 를 강제한다", userApi.requests.single().confirm)
        assertEquals(1, cleaned)
        assertEquals(0, uncertain)
        assertNull(vm.withdrawError)
        assertFalse(vm.withdrawing)
    }

    @Test
    fun `명확한 서버 거절(400) - 콜백 없이 안내 후 재시도 가능`() = runTest(dispatcher) {
        val userApi = FakeUserApi(failure = httpError(400))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0
        var uncertain = 0

        vm.withdraw(onWithdrawn = { cleaned++ }, onUncertain = { uncertain++ })
        advanceUntilIdle()

        assertEquals("서버가 거부를 응답했다 = 미처리 확실 → 세션 유지", 0, cleaned)
        assertEquals("거절은 결과 불명이 아니다", 0, uncertain)
        assertNotNull(vm.withdrawError)
        assertFalse("실패 후 재시도할 수 있어야 한다", vm.withdrawing)

        vm.dismissWithdrawError()
        assertNull(vm.withdrawError)
    }

    @Test
    fun `결과 불명(IOException) - 확인 대기 없이 즉시 안전 정리를 시작한다`() = runTest(dispatcher) {
        // 리뷰 2차 P1: 전역 라우팅이 이 VM 을 폐기하기 전에 정리가 시작돼 있어야 한다 —
        // 다이얼로그 표시·사용자 확인 같은 중간 단계 없이 catch 에서 곧바로 콜백된다.
        val userApi = FakeUserApi(failure = IOException("timeout"))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0
        var uncertain = 0

        vm.withdraw(onWithdrawn = { cleaned++ }, onUncertain = { uncertain++ })
        advanceUntilIdle()

        assertEquals(0, cleaned)
        assertEquals("catch 에서 즉시 1회 호출", 1, uncertain)
        assertNull("불명은 재시도 오류로 안내하지 않는다", vm.withdrawError)
        assertFalse(vm.withdrawing)
    }

    @Test
    fun `결과 불명(401) - 이전 탈퇴가 이미 커밋된 재시도로 보고 즉시 정리`() = runTest(dispatcher) {
        val userApi = FakeUserApi(failure = httpError(401))
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var uncertain = 0

        vm.withdraw(onWithdrawn = {}, onUncertain = { uncertain++ })
        advanceUntilIdle()

        assertEquals(1, uncertain)
        assertNull(vm.withdrawError)
    }

    @Test
    fun `결과 불명(5xx) - 500·502·503·504 모두 거절이 아니라 즉시 정리`() = runTest(dispatcher) {
        // 리뷰 2차 P1: 500 은 커밋 후 응답 생성 실패, 502-504 는 게이트웨이가 원 서버의 최종 응답을
        // 못 받은 경우가 가능하다 — 서버가 처리했는지 단말이 알 수 없다.
        for (code in listOf(500, 502, 503, 504)) {
            val userApi = FakeUserApi(failure = httpError(code))
            val vm = SettingsViewModel(StubSettingsApi(), userApi)
            var cleaned = 0
            var uncertain = 0

            vm.withdraw(onWithdrawn = { cleaned++ }, onUncertain = { uncertain++ })
            advanceUntilIdle()

            assertEquals("HTTP $code 는 결과 불명", 1, uncertain)
            assertEquals("HTTP $code 에서 성공 콜백 금지", 0, cleaned)
            assertNull("HTTP $code 는 재시도 안내 대상이 아니다", vm.withdrawError)
        }
    }

    @Test
    fun `요청 in-flight 중 VM 폐기(취소) - 그래도 안전 정리를 시작한다`() = runTest(dispatcher) {
        // 리뷰 3차 P1: 인터셉터가 UNAUTHORIZED/NETWORK/SERVER 전역 상태를 Retrofit continuation 보다
        // 먼저 발행하면, MAIN 재라우팅이 이 VM 의 ViewModelStore 를 clear 해 suspend 호출이
        // CancellationException 으로 끝난다. 요청은 이미 서버로 나갔으므로 결과 불명과 동일하게
        // Activity 범위 정리(onUncertain)가 시작돼야 한다. FakeUserApi 가 예외를 직접 던지는 위
        // 테스트들과 달리, 실제로 응답 대기 중 scope 를 취소해 이 경합을 재현한다.
        val userApi = FakeUserApi(hang = true)
        val vm = SettingsViewModel(StubSettingsApi(), userApi)
        var cleaned = 0
        var uncertain = 0

        vm.withdraw(onWithdrawn = { cleaned++ }, onUncertain = { uncertain++ })
        runCurrent() // 요청 디스패치 → 서버 응답 대기(in-flight)
        assertEquals("요청이 이미 서버로 나간 상태여야 한다", 1, userApi.calls)

        vm.viewModelScope.cancel() // 전역 라우팅의 MAIN ViewModelStore.clear() 재현
        advanceUntilIdle()

        assertEquals("취소돼도 Activity 범위 안전 정리는 시작된다", 1, uncertain)
        assertEquals("성공 콜백은 호출되지 않는다", 0, cleaned)
        assertFalse("finally 로 진행 플래그가 정리된다", vm.withdrawing)
    }

    @Test
    fun `진행 중 중복 요청은 무시된다`() = runTest(dispatcher) {
        val userApi = FakeUserApi()
        val vm = SettingsViewModel(StubSettingsApi(), userApi)

        vm.withdraw(onWithdrawn = {}, onUncertain = {})
        vm.withdraw(onWithdrawn = {}, onUncertain = {}) // 첫 요청이 아직 in-flight
        advanceUntilIdle()

        assertEquals(1, userApi.calls)
    }
}
