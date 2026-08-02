package com.aihealthcare.ah0404.fitness

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 발화 게이트(#380) 계약 — 준비 상태 전환·대기열·발화의 원자성.
 *
 * 리뷰(#382)에서 두 단계로 지적된 경쟁을 모두 고정한다:
 *  1차 — `!ready` 확인과 보관이 원자적이지 않으면, 그 사이 onInit 이 ready 로 바꾸고 빈 큐를
 *        drain 해 직후 보관된 안내가 **영구 유실**된다.
 *  2차 — 목록만 돌려주고 락 밖에서 발화하면, flush 도중 들어온 요청이 보관분보다 **먼저 발화**되고
 *        (순서 붕괴), `clearPending`(stop) **이후에도 남은 목록이 발화**된다.
 * 그래서 발화까지 같은 임계 구역 안에서 콜백으로 수행한다 — 아래 테스트가 그 계약이다.
 */
class GuideGateTest {

    /** 발화된 텍스트를 순서대로 모으는 수집기(발화 콜백 대역). */
    private fun collector(into: MutableList<String>): (String) -> Unit = { into += it }

    @Test
    fun 준비_전_요청은_보관되고_발화하지_않는다() {
        val gate = GuideGate()
        val spoken = mutableListOf<String>()

        gate.request("첫 안내", collector(spoken))

        assertTrue("준비 전에는 발화하지 않는다", spoken.isEmpty())
        assertEquals(1, gate.pendingSize())
    }

    @Test
    fun 준비되면_보관분을_순서대로_발화한다() {
        val gate = GuideGate()
        val spoken = mutableListOf<String>()
        gate.request("양팔을 가슴에 X자로 안아주세요.") {}
        gate.request("의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다.") {}

        gate.markReady(languageAvailable = true, speak = collector(spoken))

        assertEquals(
            listOf("양팔을 가슴에 X자로 안아주세요.", "의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다."),
            spoken,
        )
        assertEquals("회수 후 대기열은 비어야 한다", 0, gate.pendingSize())
    }

    @Test
    fun 준비_후_요청은_즉시_발화된다() {
        val gate = GuideGate()
        val spoken = mutableListOf<String>()
        gate.markReady(languageAvailable = true) {}

        gate.request("준비 후 안내", collector(spoken))

        assertEquals(listOf("준비 후 안내"), spoken)
        assertEquals("즉시 발화한 것은 보관하지 않는다", 0, gate.pendingSize())
    }

    @Test
    fun 언어_미지원이면_보관분도_이후_요청도_발화하지_않는다() {
        val gate = GuideGate()
        val spoken = mutableListOf<String>()
        gate.request("보관될 안내") {}

        gate.markReady(languageAvailable = false, speak = collector(spoken))
        gate.request("이후 안내", collector(spoken))

        assertTrue("한국어 미설치 — 시각 안내로만 진행한다", spoken.isEmpty())
        assertEquals(0, gate.pendingSize())
    }

    @Test
    fun clearPending_이후에는_보관분이_발화되지_않는다() {
        // 화면 이탈(stop)·소멸(shutdown) — 떠난 화면의 안내가 나중에 발화되면 안 된다.
        val gate = GuideGate()
        val spoken = mutableListOf<String>()
        gate.request("떠난 화면의 안내") {}

        gate.clearPending()
        gate.markReady(languageAvailable = true, speak = collector(spoken))

        assertTrue(spoken.isEmpty())
        assertEquals(0, gate.pendingSize())
    }

    @Test
    fun 요청과_준비완료가_교차해도_안내는_유실되지_않는다() {
        // 리뷰 1차 회귀: 확인·보관 사이 drain 으로 사라지던 지점.
        val rounds = 300
        repeat(rounds) { round ->
            val gate = GuideGate(maxPending = GUIDES_PER_ROUND) // 상한 폐기와 유실을 구분하려 충분히 크게
            val spoken = Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)

            val requester = pool.submit {
                start.await()
                repeat(GUIDES_PER_ROUND) { i -> gate.request("r$round-g$i", collector(spoken)) }
            }
            val initializer = pool.submit {
                start.await()
                gate.markReady(languageAvailable = true, speak = collector(spoken))
            }

            start.countDown()
            requester.get(5, TimeUnit.SECONDS)
            initializer.get(5, TimeUnit.SECONDS)
            pool.shutdown()

            // markReady 이후 보관된 채 남아 있으면 아무도 flush 하지 않으므로 그 자체가 영구 유실이다.
            assertEquals("round $round: 보관된 채 남은 안내가 있으면 유실이다", 0, gate.pendingSize())
            assertEquals("round $round: 요청한 안내가 모두 발화되어야 한다", GUIDES_PER_ROUND, spoken.size)
        }
    }

    @Test
    fun flush_도중_들어온_요청은_보관분_뒤에_발화된다() {
        // 리뷰 2차 회귀 ①: 락 밖에서 발화하면 flush 중 요청이 끼어들어 순서가 뒤집힌다.
        val gate = GuideGate()
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        gate.request("g1") {}
        gate.request("g2") {}
        val latecomerDone = CountDownLatch(1)

        gate.markReady(languageAvailable = true) { text ->
            spoken += text
            if (text == "g1") {
                // 첫 보관분을 발화하는 도중 다른 스레드가 요청한다 — 락 때문에 끼어들 수 없어야 한다.
                Thread {
                    gate.request("g3", collector(spoken))
                    latecomerDone.countDown()
                }.start()
                Thread.sleep(50)
                assertEquals("flush 중 요청이 끼어들면 안 된다", listOf("g1"), spoken.toList())
            }
        }

        assertTrue(latecomerDone.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("g1", "g2", "g3"), spoken.toList())
    }

    @Test
    fun 취소와_준비완료가_교차해도_취소_이후_발화는_없다() {
        // 리뷰 2차 회귀 ②: stop() 이 대기열을 비운 뒤에도 이미 꺼낸 목록이 발화되던 지점.
        //   clearPending 과 markReady 가 같은 락을 잡으므로, 발화는 항상 취소 '이전'에만 일어난다.
        repeat(200) { round ->
            val gate = GuideGate(maxPending = GUIDES_PER_ROUND)
            repeat(GUIDES_PER_ROUND) { i -> gate.request("r$round-g$i") {} }
            val cancelled = AtomicBoolean(false)
            val spokeAfterCancel = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)

            val flusher = pool.submit {
                start.await()
                gate.markReady(languageAvailable = true) {
                    if (cancelled.get()) spokeAfterCancel.set(true)
                }
            }
            val canceller = pool.submit {
                start.await()
                gate.clearPending()
                cancelled.set(true)
            }

            start.countDown()
            flusher.get(5, TimeUnit.SECONDS)
            canceller.get(5, TimeUnit.SECONDS)
            pool.shutdown()

            assertFalse("round $round: 취소 이후 발화가 있으면 안 된다", spokeAfterCancel.get())
            assertEquals("round $round: 취소 후 대기열은 비어야 한다", 0, gate.pendingSize())
        }
    }

    private companion object {
        const val GUIDES_PER_ROUND = 8
    }
}
