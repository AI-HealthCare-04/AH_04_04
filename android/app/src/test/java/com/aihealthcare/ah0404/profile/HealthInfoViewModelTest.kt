package com.aihealthcare.ah0404.profile

import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.HealthProfilePatchRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 신체 정보 편집(#기록탭 §2) 앱측 입력 가드 검증. 저장 전 검증은 코루틴 진입 전에 동기적으로 이뤄진다. */
class HealthInfoViewModelTest {

    private class FakeApi : HealthProfileApi {
        override suspend fun getLatest() = HealthProfileLatest()
        override suspend fun updateProfile(body: HealthProfilePatchRequest) = HealthProfileLatest()
    }

    @Test
    fun rejects_nonpositive_height_or_weight_before_network() {
        val vm = HealthInfoViewModel(FakeApi())

        vm.save("0", "60", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)

        vm.save("170", "", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)
    }
}
