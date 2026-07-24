package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #153 후속 — 무한루프 방지: 세션 리셋/로그아웃 후 화면 재진입 시 이전 온보딩 잔여 상태를
 * WELCOME 으로 초기화하는 [OnboardingViewModel.resetToWelcome] 검증.
 *
 *  실제 루프는 (토큰 없음 + stale step) 조합에서 났다: '홈으로 시작하기'가 토큰 없는 완료로 처리돼
 *  LOGIN_REQUIRED 로 튕기고, 리셋하면 다시 그 화면이 떠 돌았다. 화면 진입 가드가 이 함수를 불러 끊는다.
 *  (가드 자체는 Composable 이라 여기선 상태 초기화 계약만 고정한다.)
 */
class OnboardingResetTest {

    private fun vm() = OnboardingViewModel(todayYear = 2026, todayMonth = 7, todayDay = 24)

    @Test
    fun `resetToWelcome 는 WELCOME 으로 되돌리고 이전 입력을 비운다`() {
        val vm = vm()
        // 이전 사람이 남긴 입력(한 폰 다인 시연 — PII 잔존 방지 대상)
        vm.birthYear = "1950"; vm.birthMonth = "3"; vm.birthDay = "5"
        vm.sex = "female"
        vm.waistCm = "80"
        vm.walkingPractice = true
        vm.strengthExercise = false
        vm.kidneyStatus = "dialysis"
        vm.proteinStatus = "restricted"
        vm.chairStandSec = "12.5"

        vm.resetToWelcome()

        assertEquals(OnbStep.WELCOME, vm.step)
        assertFalse("게스트 플래그도 초기화", vm.isGuest)
        assertEquals("", vm.birthYear)
        assertEquals("", vm.birthMonth)
        assertEquals("", vm.birthDay)
        assertNull(vm.sex)
        assertEquals("", vm.waistCm)
        assertNull(vm.walkingPractice)
        assertNull(vm.strengthExercise)
        assertEquals("unknown", vm.kidneyStatus)
        assertEquals("unknown", vm.proteinStatus)
        assertEquals("", vm.chairStandSec)
    }

    @Test
    fun `초기 상태는 WELCOME 이고 resetToWelcome 는 멱등이다`() {
        val vm = vm()
        assertEquals(OnbStep.WELCOME, vm.step)
        vm.resetToWelcome()
        assertEquals(OnbStep.WELCOME, vm.step)
        assertFalse(vm.isGuest)
    }
}
