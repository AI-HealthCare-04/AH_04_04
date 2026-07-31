package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 약관 '보기' 링크 안전 경계(리뷰 #303) — 두 계약을 순수 함수로 고정한다.
 *  1. URL allowlist: 서버 환경변수 문자열이 그대로 오므로 https + 운영 호스트만 외부 인텐트 허용.
 *  2. 접근성 이름: 반복되는 '보기' 대신 약관 제목이 포함된 이름(TalkBack 구분 가능).
 */
class OnboardingTermsLinkTest {

    private val host = "aigo-health.duckdns.org"

    @Test
    fun allows_only_https_on_terms_host() {
        assertTrue(isAllowedTermsUrl("https://aigo-health.duckdns.org/terms/privacy-1.0", host))
        assertTrue("호스트 대소문자는 무시", isAllowedTermsUrl("HTTPS://AIGO-HEALTH.DUCKDNS.ORG/terms/x", host))
    }

    @Test
    fun rejects_non_https_schemes() {
        assertFalse("http 다운그레이드 거부", isAllowedTermsUrl("http://aigo-health.duckdns.org/terms/x", host))
        assertFalse("intent 스킴 거부", isAllowedTermsUrl("intent://evil#Intent;scheme=http;end", host))
        assertFalse("javascript 스킴 거부", isAllowedTermsUrl("javascript:alert(1)", host))
        assertFalse("커스텀 스킴 거부", isAllowedTermsUrl("myapp://terms", host))
    }

    @Test
    fun rejects_other_hosts_and_malformed() {
        assertFalse("타 호스트 거부(placeholder 포함)", isAllowedTermsUrl("https://example.com/terms/service-1.0", host))
        assertFalse("서브도메인 우회 거부", isAllowedTermsUrl("https://aigo-health.duckdns.org.evil.com/x", host))
        assertFalse("userinfo 우회 거부", isAllowedTermsUrl("https://aigo-health.duckdns.org@evil.com/x", host))
        assertFalse("빈 문자열 거부", isAllowedTermsUrl("", host))
        assertFalse("파싱 불가 거부", isAllowedTermsUrl("ht tp://broken url", host))
    }

    @Test
    fun a11y_label_includes_terms_title() {
        assertEquals("서비스 이용약관 전문 보기", termsViewA11yLabel("서비스 이용약관", "service"))
        assertEquals("제목 없으면 terms_type 으로 폴백", "privacy 전문 보기", termsViewA11yLabel(null, "privacy"))
    }
}
