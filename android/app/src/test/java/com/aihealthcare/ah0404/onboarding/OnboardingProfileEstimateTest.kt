package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SocialLoginRequest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 온보딩 키·몸무게 '모름' 추정치 로직 (백엔드 무변경, 방식 A) — 리뷰 #75 반영.
 *  - estimateBody 표(성별·연령대)
 *  - 추정은 제출/표시 시점의 최종 성별·생년월일로 라이브 계산(#75-2 입력순서 의존 제거)
 *  - 만 나이 월/일 반영(#75-3 75세 경계)
 *  - '모름'은 유효 성별·생년월일 있어야 활성
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingProfileEstimateTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi : OnboardingApi {
        override suspend fun guestLogin() = TODO()
        override suspend fun loginGoogle(body: SocialLoginRequest) = TODO()
        override suspend fun loginKakao(body: SocialLoginRequest) = TODO()
        override suspend fun getTerms() = TODO()
        override suspend fun agreeTerms(body: AgreementsRequest) = TODO()
        override suspend fun createSession(body: SessionCreateRequest) = TODO()
        override suspend fun skipHealthCheck(sessionId: Int) = TODO()
        override suspend fun createHealthProfile(body: HealthProfileRequest) = TODO()
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) = TODO()
        override suspend fun getHome() = TODO()
    }

    private fun vm(y: Int = 2026, m: Int = 7, d: Int = 15) =
        OnboardingViewModel(FakeApi(), todayYear = y, todayMonth = m, todayDay = d)

    // ── estimateBody 표 ──────────────────────────────────────────────
    @Test fun estimate_male_65_74() = assertEquals(166 to 65, estimateBody("male", 68))
    @Test fun estimate_male_75plus() = assertEquals(163 to 62, estimateBody("male", 80))
    @Test fun estimate_female_65_74() = assertEquals(153 to 56, estimateBody("female", 68))
    @Test fun estimate_female_75plus() = assertEquals(150 to 53, estimateBody("female", 82))
    @Test fun estimate_null_sex_falls_back_to_male() = assertEquals(166 to 65, estimateBody(null, 68))
    @Test fun estimate_null_age_uses_65_74_band() = assertEquals(166 to 65, estimateBody("male", null))

    // ── '모름' 활성 조건 ─────────────────────────────────────────────
    @Test
    fun unknown_disabled_until_sex_and_birth() {
        val vm = vm()
        assertFalse(vm.canEstimate)       // 성별·생일 없음
        vm.markHeightUnknown()
        assertFalse(vm.heightEstimated)   // 무시됨
        vm.apply { sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1" }
        assertTrue(vm.canEstimate)
    }

    // ── 추정 플래그/표시값 ───────────────────────────────────────────
    @Test
    fun height_unknown_sets_flag_and_shows_estimate() {
        val vm = vm().apply { sex = "female"; birthYear = "1958"; birthMonth = "3"; birthDay = "1" } // 68 → 65-74
        vm.markHeightUnknown()
        assertTrue(vm.heightEstimated)
        assertTrue(vm.hasEstimatedValue)
        assertEquals("153", vm.heightInput) // 여 65-74
    }

    @Test
    fun manual_input_clears_estimate() {
        val vm = vm().apply { sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1" }
        vm.markHeightUnknown()
        assertTrue(vm.heightEstimated)
        vm.setHeight("170")
        assertFalse(vm.heightEstimated)
        assertEquals("170", vm.heightInput)
        assertFalse(vm.hasEstimatedValue)
    }

    // ── #75-2: 추정 후 성별·생년월일 변경 시 라이브 재계산 ────────────────
    @Test
    fun estimate_recomputes_when_demographics_change() {
        val vm = vm().apply { sex = "male"; birthYear = "1960"; birthMonth = "1"; birthDay = "1" } // 66 → 65-74
        vm.markHeightUnknown()
        assertEquals("166", vm.heightInput)            // 남 65-74
        vm.sex = "female"; vm.birthYear = "1945"        // 81 → 75+
        assertEquals("150", vm.heightInput)            // 여 75+ 로 재계산(고정 안 됨)
    }

    // ── #75-3: 만 나이 75세 경계(생일 전/당일) ─────────────────────────
    @Test
    fun age_boundary_before_birthday_uses_younger_band() {
        // 오늘 2026-07-15, 생일 07-16(아직 안 지남) → 74세 → 65-74
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1951"; birthMonth = "7"; birthDay = "16" }
        vm.markHeightUnknown()
        assertEquals("166", vm.heightInput)
    }

    @Test
    fun age_boundary_on_birthday_uses_older_band() {
        // 오늘 2026-07-15, 생일 07-15(오늘) → 75세 → 75+
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1951"; birthMonth = "7"; birthDay = "15" }
        vm.markHeightUnknown()
        assertEquals("163", vm.heightInput)
    }

    // ── #298 C: 추정('모름')은 만 50세 이상(50~64 추정표 확장). 50세 미만만 직접 입력 ──────────
    @Test
    fun under_50_cannot_estimate() {
        // 오늘 2026 기준 1990년생 → 36세. 성별·생일 유효해도 추정 근거 없어 '모름' 불가.
        val vm = vm().apply { sex = "male"; birthYear = "1990"; birthMonth = "1"; birthDay = "1" }
        assertFalse(vm.canEstimate)
        vm.markHeightUnknown()
        assertFalse(vm.heightEstimated) // 무시됨
    }

    @Test
    fun age_49_cannot_estimate_but_50_can() {
        val at49 = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1977"; birthMonth = "7"; birthDay = "16" } // 48
        assertFalse(at49.canEstimate)
        val at50 = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1976"; birthMonth = "7"; birthDay = "15" } // 50
        assertTrue(at50.canEstimate)
    }

    @Test
    fun submit_allows_under_65_without_age_block() = runTest {
        // #298 C: 만 65세 미만이라고 프로필 제출을 막지 않는다. (FakeApi 는 네트워크에서 TODO 라 저장 자체는
        //   실패하지만, '만 65세 이상' 차단 문구가 뜨면 안 된다 — 나이 게이트가 사라졌음을 검증.)
        val vm = vm(2026, 7, 15).apply {
            birthYear = "1990"; birthMonth = "1"; birthDay = "1" // 36세
            sex = "male"; walkDays = 5; muscDays = 2
            setHeight("170"); setWeight("65")
        }
        vm.submitProfile(); advanceUntilIdle()
        assertFalse("65세 미만 차단 문구가 뜨면 안 됨(#298 C)", vm.error?.contains("65세") == true)
    }

    // ── #267 지영님 블로커: 활동 일수 미응답(null)은 제출 차단 — '미응답'과 '주 0일'을 구분해야 예측 입력이 왜곡되지 않는다 ──
    @Test
    fun submit_rejects_when_activity_days_unanswered() = runTest {
        val vm = vm().apply {
            birthYear = "1958"; birthMonth = "3"; birthDay = "1" // 68세
            sex = "male"; setHeight("170"); setWeight("65")
            // walkDays·muscDays 는 기본 null(미응답) — 사용자가 스테퍼를 안 건드린 상태
        }
        vm.submitProfile(); advanceUntilIdle()
        assertTrue(vm.error?.contains("일수를 선택") == true)
        assertFalse(vm.step == OnbStep.ASSESSMENT)
    }

    // 0일("안 해요")도 사용자가 명시적으로 고르면 정상 통과해야 한다(0 자체는 유효한 답).
    @Test
    fun submit_accepts_explicit_zero_activity_days() = runTest {
        val vm = vm().apply {
            birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            sex = "male"; setHeight("170"); setWeight("65")
            walkDays = 0; muscDays = 0
        }
        vm.submitProfile(); advanceUntilIdle()
        // 활동 일수 검증은 통과 — 이후 실패해도 '일수를 선택' 에러는 아니어야 한다(FakeApi 는 네트워크에서 TODO).
        assertFalse("0일도 유효 응답 — 일수 미선택 에러가 뜨면 안 됨", vm.error?.contains("일수를 선택") == true)
    }

    @Test
    fun waist_unknown_clears_field() {
        val vm = vm().apply { waistCm = "88" }
        vm.markWaistUnknown()
        assertEquals("", vm.waistCm)
    }

    // ── #298 A-2: 키·몸무게 현실 범위 밖(0·음수·극단값)은 백엔드 이전에 앱에서 차단 ──────────
    @Test
    fun submit_rejects_out_of_range_height() = runTest {
        val vm = vm().apply {
            birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            sex = "male"; walkDays = 5; muscDays = 2
            setWeight("60"); setHeight("0") // 90cm 미만 → 거부
        }
        vm.submitProfile(); advanceUntilIdle()
        assertTrue("키 범위 안내 문구", vm.error?.contains("cm 사이") == true)
        assertFalse(vm.step == OnbStep.ASSESSMENT)
    }

    @Test
    fun submit_rejects_over_range_weight() = runTest {
        val vm = vm().apply {
            birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            sex = "male"; walkDays = 5; muscDays = 2
            setHeight("170"); setWeight("999") // 200kg 초과 → 거부(DB Numeric(5,2) 초과 500 이전 방어)
        }
        vm.submitProfile(); advanceUntilIdle()
        assertTrue("몸무게 범위 안내 문구", vm.error?.contains("kg 사이") == true)
        assertFalse(vm.step == OnbStep.ASSESSMENT)
    }
}
