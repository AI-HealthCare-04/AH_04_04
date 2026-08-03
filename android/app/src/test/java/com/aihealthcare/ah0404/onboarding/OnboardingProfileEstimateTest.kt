package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.HealthProfileResponse
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
import org.junit.Assert.assertNull
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

    // ── KNHANES 2022~2024 단일나이 ±3세 추정표(#326) ─────────────────
    @Test fun estimate_male_50() = assertEquals(172.5 to 74.5, estimateBody("male", 50))
    @Test fun estimate_male_59() = assertEquals(169.8 to 70.6, estimateBody("male", 59))
    @Test fun estimate_male_60() = assertEquals(169.6 to 70.4, estimateBody("male", 60))
    @Test fun estimate_male_64() = assertEquals(168.7 to 69.2, estimateBody("male", 64))
    @Test fun estimate_female_50() = assertEquals(159.5 to 58.0, estimateBody("female", 50))
    @Test fun estimate_female_64() = assertEquals(156.0 to 57.9, estimateBody("female", 64))
    @Test fun estimate_male_79() = assertEquals(165.9 to 65.3, estimateBody("male", 79))
    @Test fun estimate_male_80plus() = assertEquals(164.0 to 61.2, estimateBody("male", 80))
    @Test fun estimate_female_80plus() = assertEquals(149.3 to 53.0, estimateBody("female", 82))
    @Test fun estimate_null_sex_falls_back_to_male() = assertEquals(167.6 to 67.8, estimateBody(null, 68))
    @Test fun estimate_null_age_uses_age_65() = assertEquals(168.5 to 68.9, estimateBody("male", null))

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
        val vm = vm().apply { sex = "female"; birthYear = "1958"; birthMonth = "3"; birthDay = "1" } // 68세
        vm.markHeightUnknown()
        assertTrue(vm.heightEstimated)
        assertTrue(vm.hasEstimatedValue)
        assertEquals("154.9", vm.heightInput)
    }

    @Test
    fun integer_estimate_omits_trailing_decimal() {
        val vm = vm().apply { sex = "female"; birthYear = "1971"; birthMonth = "1"; birthDay = "1" } // 55세
        vm.markWeightUnknown()
        assertTrue(vm.weightEstimated)
        assertEquals("58", vm.weightInput)
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
        val vm = vm().apply { sex = "male"; birthYear = "1960"; birthMonth = "1"; birthDay = "1" } // 66세
        vm.markHeightUnknown()
        assertEquals("168.1", vm.heightInput)          // 남 66세
        vm.sex = "female"; vm.birthYear = "1945"        // 81 → 80+
        assertEquals("149.3", vm.heightInput)          // 여 80+ 로 재계산(고정 안 됨)
    }

    // ── #75-3: 생일 전/당일의 만 나이 경계(74 → 75) ───────────────────
    @Test
    fun age_boundary_before_birthday_uses_age_74_value() {
        // 오늘 2026-07-15, 생일 07-16(아직 안 지남) → 74세
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1951"; birthMonth = "7"; birthDay = "16" }
        vm.markHeightUnknown()
        assertEquals("166.2", vm.heightInput)
    }

    @Test
    fun age_boundary_on_birthday_uses_age_75_value() {
        // 오늘 2026-07-15, 생일 07-15(오늘) → 75세 단일나이 값
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1951"; birthMonth = "7"; birthDay = "15" }
        vm.markHeightUnknown()
        assertEquals("166.1", vm.heightInput)
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
        // #298 C: 만 65세 미만(단 14세 이상)이라고 프로필 제출을 막지 않는다. createHealthProfile 이 정상 응답하는
        //   fake 로 성공 경로를 직접 검증한다(리뷰 #313 nit: TODO() 예외에 기대던 취약 구조 제거).
        val api = object : OnboardingApi by FakeApi() {
            override suspend fun createHealthProfile(body: HealthProfileRequest) =
                HealthProfileResponse(profileId = 1, bmi = 22.5, proteinChallengeAllowed = true)
        }
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15).apply {
            birthYear = "1990"; birthMonth = "1"; birthDay = "1" // 36세
            sex = "male"; walkDays = 5; muscDays = 2
            setHeight("170"); setWeight("65")
        }
        vm.submitProfile(); advanceUntilIdle()
        assertNull("정상 저장 → 에러 없음(나이 게이트 없음)", vm.error)
        assertEquals("체력검사 단계로 진행(#298 C)", OnbStep.ASSESSMENT, vm.step)
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

    // 입력 순서 회귀(리뷰 #313): 50세+에서 '모름' 선택 후 26세로 바꾸면, 무효 추정(65–74 값)이 제출되지 않고
    //   키·몸무게 직접 입력을 요구한다 → 50세 미만 프로필에 추정값이 새는 경로 차단.
    @Test
    fun submit_rejects_stale_estimate_after_age_dropped_below_50() = runTest {
        val vm = vm(2026, 7, 15).apply {
            sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1" // 56세
            walkDays = 5; muscDays = 2
        }
        vm.markHeightUnknown(); vm.markWeightUnknown() // 56세 시점 추정 활성
        vm.birthYear = "2000" // 26세로 변경 → 추정 무효
        vm.submitProfile(); advanceUntilIdle()
        assertTrue("무효 추정은 제출 안 되고 직접 입력을 요구", vm.error?.contains("입력") == true)
        assertFalse("체력검사로 진행하지 않는다", vm.step == OnbStep.ASSESSMENT)
    }

    @Test
    fun waist_unknown_clears_field() {
        val vm = vm().apply { waistCm = "88" }
        vm.markWaistUnknown()
        assertEquals("", vm.waistCm)
    }

    // ── #395: '모름' 후 50세 미만 전환 시 '막힌 느낌' 제거(선택지 A) — 강조 안내 + 입력칸 오류로 신호 ──────
    @Test
    fun invalidated_estimate_surfaces_notice_and_field_errors() {
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1" } // 56세
        vm.markHeightUnknown(); vm.markWeightUnknown()
        assertFalse("추정 활성 시엔 무효화 아님", vm.estimateInvalidated)

        vm.birthYear = "2000" // 26세 → 추정 무효
        assertTrue("무효화 감지", vm.estimateInvalidated)
        assertFalse("유효 추정 아님", vm.heightEstimatedValid)
        assertFalse("has_estimated_value 로도 안 샌다", vm.hasEstimatedValue)
        // 강조 안내: 직접 입력 + 생년월일 재확인(오타 되돌리기)
        assertTrue(vm.estimateInvalidatedNotice?.contains("직접 입력") == true)
        assertTrue(vm.estimateInvalidatedNotice?.contains("생년월일") == true)
        // 사라진 입력칸 자체가 오류 신호를 낸다
        assertTrue(vm.heightError?.contains("직접 입력") == true)
        assertTrue(vm.weightError?.contains("직접 입력") == true)
    }

    // 화면은 무효화 안내를 일반 연령 안내보다 우선(?: )해 하나만 띄운다 — 둘 다 값이 있어야 우선 규칙이 의미가 있다.
    @Test
    fun invalidation_notice_takes_precedence_over_underage_notice() {
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1" }
        vm.markHeightUnknown()
        vm.birthYear = "2000" // 26세: 무효화 + 만 65세 미만 안내 둘 다 대상
        assertTrue(vm.estimateInvalidatedNotice != null)
        assertTrue(vm.underAgeNotice != null)
    }

    // 직접 입력으로 값을 채우면 무효화 신호가 사라진다(플래그 해제 → estimateInvalidated=false).
    @Test
    fun typing_values_clears_invalidation_signals() {
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1" }
        vm.markHeightUnknown(); vm.markWeightUnknown()
        vm.birthYear = "2000"
        assertTrue(vm.estimateInvalidated)

        vm.setHeight("170")
        assertTrue("한 칸만 채우면 아직 무효화 상태", vm.estimateInvalidated)
        assertNull("채운 칸은 오류 해제", vm.heightError)
        assertTrue("안 채운 칸은 여전히 오류", vm.weightError?.contains("직접 입력") == true)

        vm.setWeight("65")
        assertFalse("둘 다 채우면 무효화 해제", vm.estimateInvalidated)
        assertNull(vm.estimateInvalidatedNotice)
        assertNull(vm.weightError)
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
