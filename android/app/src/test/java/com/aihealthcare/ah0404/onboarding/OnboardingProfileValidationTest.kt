package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SocialLoginRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 온보딩 건강프로필 입력 UX(#298) — 순수 파생 상태 검증(네트워크 무관).
 *  A) 생년월일 즉시 검증(범위·실제 일수·동적 연도)  A-2) 키·몸무게 현실 범위
 *  B) '모름' 비활성 사유  C) 만 65세 미만 안내 + 50~64 추정 확장
 */
class OnboardingProfileValidationTest {

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

    // ── A. 생년월일 즉시 검증 ────────────────────────────────────────────
    @Test fun birthdate_error_null_while_incomplete() =
        assertNull("월·일 미입력 중엔 조용", vm().apply { birthYear = "1958" }.birthDateError)

    @Test fun birthdate_error_on_invalid_day_feb30() =
        assertNotNull(vm().apply { birthYear = "1958"; birthMonth = "2"; birthDay = "30" }.birthDateError)

    @Test fun birthdate_error_on_month_over_12() =
        assertNotNull(vm().apply { birthYear = "1958"; birthMonth = "13"; birthDay = "1" }.birthDateError)

    @Test fun birthdate_error_on_future_year() =
        assertNotNull(vm(2026, 7, 15).apply { birthYear = "2027"; birthMonth = "1"; birthDay = "1" }.birthDateError)

    @Test fun birthdate_error_on_future_date_within_current_year() =
        // 미래 생일 거부(리뷰 #313): 오늘이 2026-07-15 여도 2026-12-31 같은 올해 안 미래 날짜는 무효.
        assertNotNull(vm(2026, 7, 15).apply { birthYear = "2026"; birthMonth = "12"; birthDay = "31" }.birthDateError)

    @Test fun birthdate_ok_current_year_past_date_boundary() =
        // 올해 상한 자체는 유지(#298 A): 오늘과 같은 날짜(2026-07-15)는 미래가 아니므로 미래 사유로는 막지 않는다.
        //   (0세라 아래 최소 가입 연령 사유로는 걸리지만, 여기선 '미래 아님'만 확인 — 그 메시지가 아님을 검증.)
        assertNotNull(
            "미래는 아니지만 만 14세 미만이라 가입 연령 안내가 나온다",
            vm(2026, 7, 15).apply { birthYear = "2026"; birthMonth = "7"; birthDay = "15" }.birthDateError,
        )

    // ── A-3. 최소 가입 연령 하한(리뷰 #313, 만 14세) ──────────────────────
    @Test fun birthdate_error_under_min_signup_age() =
        // 만 6세(2020 출생) → 개인정보보호법상 하한 미만이라 가입 차단 안내.
        assertTrue(
            vm(2026, 7, 15).apply { birthYear = "2020"; birthMonth = "1"; birthDay = "1" }
                .birthDateError?.contains("14세") == true,
        )

    @Test fun birthdate_error_just_under_14() =
        // 2012-08-01 출생은 2026-07-15 기준 아직 만 13세(생일 전) → 차단.
        assertTrue(
            vm(2026, 7, 15).apply { birthYear = "2012"; birthMonth = "8"; birthDay = "1" }
                .birthDateError?.contains("14세") == true,
        )

    @Test fun birthdate_ok_at_min_signup_age_14() =
        // 2012-07-01 출생은 2026-07-15 기준 만 14세(생일 지남) → 가입 가능, 안내 없음.
        assertNull(vm(2026, 7, 15).apply { birthYear = "2012"; birthMonth = "7"; birthDay = "1" }.birthDateError)

    @Test fun birthdate_leap_day_valid() =
        assertNull("2000 은 윤년 → 2/29 유효", vm().apply { birthYear = "2000"; birthMonth = "2"; birthDay = "29" }.birthDateError)

    @Test fun birthdate_non_leap_feb29_invalid() =
        assertNotNull("1900 은 평년 → 2/29 무효", vm().apply { birthYear = "1900"; birthMonth = "2"; birthDay = "29" }.birthDateError)

    // ── A-2. 키·몸무게 현실 범위 ────────────────────────────────────────
    @Test fun height_error_below_range() = assertNotNull(vm().apply { setHeight("50") }.heightError)
    @Test fun height_error_above_range() = assertNotNull(vm().apply { setHeight("300") }.heightError)
    @Test fun height_no_error_in_range() = assertNull(vm().apply { setHeight("170") }.heightError)
    @Test fun height_no_error_when_blank() = assertNull(vm().heightError)
    @Test fun weight_error_above_range() = assertNotNull(vm().apply { setWeight("250") }.weightError)
    @Test fun weight_no_error_in_range() = assertNull(vm().apply { setWeight("62") }.weightError)

    // ── B. '모름' 비활성 사유 ───────────────────────────────────────────
    @Test fun estimate_reason_when_missing_sex_or_birth() =
        assertTrue(vm().estimateUnavailableReason?.contains("성별") == true)

    @Test fun estimate_reason_direct_input_below_50() =
        assertTrue(
            vm(2026, 7, 15).apply { sex = "male"; birthYear = "1990"; birthMonth = "1"; birthDay = "1" } // 36
                .estimateUnavailableReason?.contains("직접") == true,
        )

    @Test fun estimate_reason_null_when_can_estimate() =
        assertNull(
            vm(2026, 7, 15).apply { sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1" } // 68
                .estimateUnavailableReason,
        )

    // ── C. 만 65세 미만 안내 + 50~64 추정 확장 ──────────────────────────
    @Test fun under_age_notice_shown_below_65() =
        assertNotNull(vm(2026, 7, 15).apply { birthYear = "1970"; birthMonth = "1"; birthDay = "1" }.underAgeNotice) // 56

    @Test fun under_age_notice_absent_at_65_plus() =
        assertNull(vm(2026, 7, 15).apply { birthYear = "1958"; birthMonth = "3"; birthDay = "1" }.underAgeNotice) // 68

    @Test fun under_age_notice_absent_when_birthdate_invalid() =
        assertNull(vm().apply { birthYear = "1958"; birthMonth = "13"; birthDay = "1" }.underAgeNotice)

    @Test
    fun estimate_available_at_63_reuses_65_74_table() {
        val vm = vm(2026, 7, 15).apply { sex = "female"; birthYear = "1963"; birthMonth = "1"; birthDay = "1" } // 63
        assertTrue("50~64 도 추정 가능(#298 C)", vm.canEstimate)
        vm.markHeightUnknown()
        assertTrue(vm.heightEstimated)
        assertEquals("50~64 는 65–74 추정치 재사용(여 153cm)", "153", vm.heightInput)
    }

    @Test
    fun estimate_blocked_below_50() {
        val vm = vm(2026, 7, 15).apply { sex = "male"; birthYear = "1990"; birthMonth = "1"; birthDay = "1" } // 36
        assertFalse(vm.canEstimate)
        vm.markHeightUnknown()
        assertFalse("50 미만은 추정 입력 무시", vm.heightEstimated)
    }
}
