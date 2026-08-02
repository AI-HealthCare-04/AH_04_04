package com.aihealthcare.ah0404.fitness

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 발화 게이트(#380) 계약 — 준비 상태 전환과 대기열 조작의 원자성.
 *
 * 리뷰(#382)에서 지적된 교차가 핵심이다: `!ready` 확인과 보관이 원자적이지 않으면 그 사이에
 * onInit 이 ready 로 바꾸고 빈 큐를 drain 해, 직후 보관된 안내가 **영구 유실**된다.
 * 두 진입점(request / markReady)이 같은 락을 잡는지 단일 스레드 계약 + 교차 실행으로 검증한다.
 */
class GuideGateTest {

    @Test
    fun 준비_전_요청은_보관되고_발화하지_않는다() {
        val gate = GuideGate()

        assertNull("준비 전에는 지금 발화할 것이 없다", gate.request("첫 안내"))
        assertEquals(1, gate.pendingSize())
    }

    @Test
    fun 준비되면_보관분을_순서대로_돌려준다() {
        val gate = GuideGate()
        gate.request("양팔을 가슴에 X자로 안아주세요.")
        gate.request("의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다.")

        val toSpeak = gate.markReady(languageAvailable = true)

        assertEquals(
            listOf("양팔을 가슴에 X자로 안아주세요.", "의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다."),
            toSpeak,
        )
        assertEquals("회수 후 대기열은 비어야 한다", 0, gate.pendingSize())
    }

    @Test
    fun 준비_후_요청은_즉시_발화_대상이_된다() {
        val gate = GuideGate()
        gate.markReady(languageAvailable = true)

        assertEquals("준비 후 안내", gate.request("준비 후 안내"))
        assertEquals("즉시 발화 대상은 보관하지 않는다", 0, gate.pendingSize())
    }

    @Test
    fun 언어_미지원이면_보관분도_이후_요청도_발화하지_않는다() {
        val gate = GuideGate()
        gate.request("보관될 안내")

        assertTrue("한국어 미설치 — 보관분은 버린다", gate.markReady(languageAvailable = false).isEmpty())
        assertNull("이후 요청도 발화하지 않는다(시각 안내로 폴백)", gate.request("이후 안내"))
        assertEquals(0, gate.pendingSize())
    }

    @Test
    fun clearPending_은_보관분을_버린다() {
        // 화면 이탈(stop)·소멸(shutdown) — 떠난 화면의 안내가 나중에 발화되면 안 된다.
        val gate = GuideGate()
        gate.request("떠난 화면의 안내")

        gate.clearPending()

        assertEquals(0, gate.pendingSize())
        assertTrue(gate.markReady(languageAvailable = true).isEmpty())
    }

    @Test
    fun 요청과_준비완료가_교차해도_안내는_유실되지_않는다() {
        // 리뷰 P1 회귀: request 와 markReady 를 여러 스레드에서 동시에 때려도,
        //   모든 안내는 '즉시 발화' 또는 '보관 후 회수' 중 정확히 한 경로로 나와야 한다.
        //   (원자적이지 않던 구현에서는 확인·보관 사이 drain 으로 일부가 사라졌다.)
        val rounds = 300
        repeat(rounds) { round ->
            val gate = GuideGate(maxPending = GUIDES_PER_ROUND) // 상한 폐기와 유실을 구분하기 위해 충분히 크게
            val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)

            val requester = pool.submit {
                start.await()
                repeat(GUIDES_PER_ROUND) { i ->
                    gate.request("r$round-g$i")?.let { spoken += it }
                }
            }
            val initializer = pool.submit {
                start.await()
                spoken += gate.markReady(languageAvailable = true)
            }

            start.countDown()
            requester.get(5, TimeUnit.SECONDS)
            initializer.get(5, TimeUnit.SECONDS)
            pool.shutdown()

            // markReady 이후 보관된 것이 남아 있으면 그것도 영구 유실이다(아무도 flush 하지 않음).
            assertEquals("round $round: 보관된 채 남은 안내가 있으면 유실이다", 0, gate.pendingSize())
            assertEquals(
                "round $round: 요청한 안내가 모두 발화 경로로 나와야 한다",
                GUIDES_PER_ROUND,
                spoken.size,
            )
        }
    }

    private companion object {
        const val GUIDES_PER_ROUND = 8
    }
}
