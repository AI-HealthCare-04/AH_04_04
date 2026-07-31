package com.aihealthcare.ah0404.profile

import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.HealthProfilePatchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 신체 정보 편집(#기록탭 §2) 앱측 입력 가드 + 단백질 제한 전송(#304) 검증. */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthInfoViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi : HealthProfileApi {
        var lastBody: HealthProfilePatchRequest? = null
        override suspend fun getLatest() = HealthProfileLatest()
        override suspend fun updateProfile(body: HealthProfilePatchRequest): HealthProfileLatest {
            lastBody = body
            return HealthProfileLatest()
        }
    }

    @Test
    fun rejects_nonpositive_height_or_weight_before_network() {
        val vm = HealthInfoViewModel(FakeApi())

        vm.save("0", "60", "", "none", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)

        vm.save("170", "", "", "none", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)
    }

    @Test
    fun `저장 시 신장과 단백질 제한을 함께 전송한다`() = runTest(dispatcher) {
        // #304: 단백질 제한을 편집·전송해야 신장 되돌림 후 미션이 다시 열린다.
        val api = FakeApi()
        val vm = HealthInfoViewModel(api)

        vm.save("170", "68", "", "none", "restricted") {}
        advanceUntilIdle()

        assertEquals("none", api.lastBody?.kidneyStatus)
        assertEquals("restricted", api.lastBody?.proteinRestrictionStatus)
    }
}
