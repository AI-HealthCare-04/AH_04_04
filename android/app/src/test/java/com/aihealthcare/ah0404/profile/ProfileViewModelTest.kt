package com.aihealthcare.ah0404.profile

import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserInfoResponse
import com.aihealthcare.ah0404.network.UserUpdateRequest
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

    /**
     * 리뷰 #70 지적 1: 재진입 GET 과 닉네임 PATCH 가 겹칠 때, 저장된 새 이름이 늦은 GET 응답으로
     * 이전 이름으로 되돌아가면 안 된다. gate 로 두 번째 GET(이전 이름)을 잡아두고 그 사이 저장 성공,
     * gate 를 풀어도 최종 nickname 은 새 이름이어야 한다.
     */
    private class GatedGetApi(private val gate: CompletableDeferred<Unit>, private val old: UserInfoResponse) : UserApi {
        var meCalls = 0
        override suspend fun getMe(): UserInfoResponse {
            meCalls++
            if (meCalls >= 2) gate.await() // 두 번째 조회는 gate 가 풀릴 때까지 대기(느린 GET)
            return old
        }
        override suspend fun updateMe(body: UserUpdateRequest): UserInfoResponse =
            old.copy(nickname = body.nickname)
    }

    @Test
    fun save_wins_over_late_reentry_get() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = GatedGetApi(gate, info(nickname = "홍길동"))
        val vm = ProfileViewModel(api)

        vm.refresh() // 1차 로드: 홍길동
        assertEquals("홍길동", vm.info?.nickname)

        val reentry = launch { vm.refresh() } // 2차(느린) GET → gate 대기
        while (api.meCalls < 2) yield()

        val ok = vm.saveNickname("김철수") // 저장 성공 → 새 이름 + version 무효화
        assertTrue(ok)
        assertEquals("김철수", vm.info?.nickname)

        gate.complete(Unit) // 느린 GET 완료(이전 이름) → 최신 version 이 아니라 반영 안 됨
        reentry.join()

        assertEquals("김철수", vm.info?.nickname) // 이전 이름으로 되돌아가지 않음
    }

    /**
     * 리뷰 #70 지적 2: 캐시된 info 가 있는 상태에서 재조회가 실패하면 error 를 노출해야 한다
     * (info 는 유지하되 error=true). 화면은 이 상태에서 재조회 실패 배너+재시도를 보여준다.
     */
    @Test
    fun reentry_failure_sets_error_while_keeping_info() = runBlocking {
        var fail = false
        val api = FakeUserApi(me = { if (fail) throw RuntimeException("boom") else info(nickname = "홍길동") })
        val vm = ProfileViewModel(api)

        vm.refresh()
        assertEquals("홍길동", vm.info?.nickname)
        assertFalse(vm.error)

        fail = true
        vm.refresh()

        assertTrue(vm.error)                       // 재조회 실패 노출
        assertEquals("홍길동", vm.info?.nickname)  // 기존 info 유지(사라지지 않음)
    }
}
