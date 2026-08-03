package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로그인 전 문의처(#387) 회귀 방지.
 *
 * 이 이슈의 뿌리는 "링크가 죽어 있다"와 "문의처가 placeholder 라 메일이 가지 않는다" 둘이었다.
 * 화면 배선은 실기기 QA 로 확인하고, 여기서는 **주소가 다시 예약 도메인으로 돌아가지 않는지**를 고정한다.
 */
class LoginSupportContactTest {

    @Test
    fun `문의 주소는 예약 도메인(placeholder)이 아니다`() {
        // .example.com / .example.org 등은 RFC 2606 예약 도메인이라 실제로 메일이 가지 않는다.
        assertFalse(LOGIN_SUPPORT_EMAIL.contains("example.com"))
        assertFalse(LOGIN_SUPPORT_EMAIL.contains("example.org"))
        assertTrue(LOGIN_SUPPORT_EMAIL.contains("@"))
    }

    @Test
    fun `다이얼로그 문구에 주소와 대안 안내가 함께 있다`() {
        // 메일 앱이 없는 기기에서도 주소를 눈으로 읽어 옮겨 적을 수 있어야 한다.
        assertTrue(LOGIN_SUPPORT_DIALOG_MESSAGE.contains(LOGIN_SUPPORT_EMAIL))
        assertTrue(LOGIN_SUPPORT_DIALOG_MESSAGE.contains("직접"))
    }
}
