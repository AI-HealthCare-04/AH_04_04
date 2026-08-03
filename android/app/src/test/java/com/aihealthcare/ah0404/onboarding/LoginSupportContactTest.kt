package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.settings.SUPPORT_DEMO_NOTICE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로그인 전 안내(#387) 회귀 방지.
 *
 * 이 이슈의 뿌리는 "링크가 죽어 있다"와 "문의처가 placeholder 라 메일이 가지 않는다" 둘이었다.
 * 후자는 **문의 창구를 두지 않는 것**으로 정리했다(리뷰 P1) — 심사·시연용 앱이라 받을 메일함이 없는데
 * 주소를 적으면 '죽은 링크'가 '보내도 아무도 안 읽는 주소'로 바뀔 뿐이다.
 * 그래서 여기서는 **안내 문구에 메일 주소가 다시 들어오지 않는지**를 고정한다.
 * 화면 배선(눌리는지·다이얼로그가 뜨는지)은 실기기 QA 로 확인한다.
 */
class LoginSupportContactTest {

    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    @Test
    fun `로그인 전 안내에 메일 주소를 넣지 않는다`() {
        // 주소가 다시 들어오면 '받지 못하는 창구'를 안내하는 상태로 되돌아간다.
        assertFalse(
            "안내 문구에 메일 주소가 있으면 안 된다: $LOGIN_SUPPORT_DIALOG_MESSAGE",
            emailPattern.containsMatchIn(LOGIN_SUPPORT_DIALOG_MESSAGE),
        )
    }

    @Test
    fun `안내는 접수하지 않는다는 사실과 지금 할 수 있는 일을 함께 말한다`() {
        // 사실만 알리고 끝내면 로그인 실패 시 출구가 없던 원래 문제(#387)로 돌아간다.
        assertTrue(LOGIN_SUPPORT_DIALOG_MESSAGE.contains("운영하지 않아요"))
        assertTrue("다시 시도·다른 로그인 방법을 안내한다", LOGIN_SUPPORT_DIALOG_MESSAGE.contains("다시 시도"))
        assertTrue("로그인 없이 둘러보는 경로도 알려준다", LOGIN_SUPPORT_DIALOG_MESSAGE.contains("체험으로 시작하기"))
    }

    @Test
    fun `고객센터 화면 안내도 같은 방침이다`() {
        // 로그인 전(온보딩)과 로그인 후(설정 → 고객센터)가 다른 말을 하면 사용자가 어디가 맞는지 알 수 없다.
        assertFalse(
            "고객센터 안내에도 메일 주소가 있으면 안 된다: $SUPPORT_DEMO_NOTICE",
            emailPattern.containsMatchIn(SUPPORT_DEMO_NOTICE),
        )
        assertTrue(SUPPORT_DEMO_NOTICE.contains("운영하지 않아요"))
        assertTrue("화면 안에서 할 수 있는 일(FAQ)로 보낸다", SUPPORT_DEMO_NOTICE.contains("자주 묻는 질문"))
    }
}
