package com.aihealthcare.ah0404.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 초기화 전 안내문 보관 대기열(#380) 계약.
 *
 * 기록 탭 재측정 진입은 화면에 들어오자마자 첫 안내를 요청하는데, 엔진이 아직 준비되지 않아
 * 발화가 통째로 유실됐다. 준비 전 요청을 **순서대로 보관했다가** 준비되면 그대로 발화하는 것이
 * 이 대기열의 역할이다. (엔진 자체는 JVM 테스트에서 띄울 수 없어 자료구조 계약만 고정한다 —
 * 실기기 확인은 QA 체크리스트.)
 */
class PendingGuidesTest {

    @Test
    fun 보관한_안내는_순서대로_꺼내진다() {
        val pending = PendingGuides()
        pending.hold("양팔을 가슴에 X자로 안아주세요.")
        pending.hold("의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다.")

        assertEquals(
            listOf("양팔을 가슴에 X자로 안아주세요.", "의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다."),
            pending.drain(),
        )
    }

    @Test
    fun 한_번_꺼내면_비워져_중복_발화되지_않는다() {
        val pending = PendingGuides()
        pending.hold("안내")

        assertEquals(listOf("안내"), pending.drain())
        assertTrue("두 번째 drain 은 비어 있어야 한다", pending.drain().isEmpty())
        assertEquals(0, pending.size)
    }

    @Test
    fun 상한을_넘으면_오래된_것부터_버린다() {
        // 초기화가 비정상적으로 지연되는 기기에서 안내가 무한히 쌓였다가 쏟아지는 것을 막는다.
        val pending = PendingGuides(maxSize = 2)
        pending.hold("1")
        pending.hold("2")
        pending.hold("3")

        assertEquals(2, pending.size)
        assertEquals(listOf("2", "3"), pending.drain())
    }

    @Test
    fun clear_는_보관분을_버린다() {
        // 화면 이탈(stop)·소멸(shutdown) 시 호출 — 떠난 화면의 안내가 나중에 발화되면 안 된다.
        val pending = PendingGuides()
        pending.hold("떠난 화면의 안내")

        pending.clear()

        assertEquals(0, pending.size)
        assertTrue(pending.drain().isEmpty())
    }

    @Test
    fun 기본_상한은_한_단계_안내를_담기에_충분하다() {
        // GUIDE 단계는 안내문 2건을 연달아 요청한다 — 기본 상한이 그보다 작으면 첫 안내가 또 유실된다.
        val pending = PendingGuides()
        repeat(2) { pending.hold("안내 $it") }

        assertEquals(2, pending.size)
        assertTrue(PendingGuides.MAX_PENDING >= 2)
    }
}
