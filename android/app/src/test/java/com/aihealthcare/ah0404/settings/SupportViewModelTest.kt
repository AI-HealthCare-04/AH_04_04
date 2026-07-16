package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.FaqItem
import com.aihealthcare.ah0404.network.FaqListResponse
import com.aihealthcare.ah0404.network.SupportApi
import com.aihealthcare.ah0404.network.SupportResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SupportViewModel 테스트 — GET /support, GET /support/faqs(#74) 독립 조회.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(
        val support: () -> SupportResponse,
        val faqs: () -> FaqListResponse,
    ) : SupportApi {
        override suspend fun getSupport() = support()
        override suspend fun getFaqs() = faqs()
    }

    private fun faq(id: Int) = FaqItem(id, "q$id", "a$id")

    @Test
    fun loads_email_and_sorts_faqs_by_id() = runTest {
        val vm = SupportViewModel(
            FakeApi(
                support = { SupportResponse("help@aigo.test") },
                faqs = { FaqListResponse(listOf(faq(3), faq(1), faq(2))) },
            ),
        )
        vm.load(); advanceUntilIdle()

        assertEquals("help@aigo.test", vm.email)
        assertEquals(listOf(1, 2, 3), vm.faqs.map { it.faqId })
        assertFalse(vm.faqsError)
        assertTrue(vm.loaded)
    }

    @Test
    fun faqs_failure_keeps_email_and_flags_error() = runTest {
        val vm = SupportViewModel(
            FakeApi(
                support = { SupportResponse("help@aigo.test") },
                faqs = { throw RuntimeException("boom") },
            ),
        )
        vm.load(); advanceUntilIdle()

        assertEquals("help@aigo.test", vm.email) // 이메일은 정상 반영
        assertTrue(vm.faqsError)                  // FAQ 만 오류
        assertTrue(vm.faqs.isEmpty())
    }

    /**
     * 리뷰 #77: 오래된 refresh 가 최신 요청의 loading 을 끄거나 결과를 덮으면 안 된다.
     * gen1(느린 FAQ)을 gate 로 잡고 그 사이 gen2(빠름) 완료 → gen1 을 풀어도 gen2 결과·loading 유지.
     */
    @Test
    fun stale_refresh_does_not_override_latest() = runTest {
        val gate = CompletableDeferred<Unit>()
        var faqCalls = 0
        val api = object : SupportApi {
            override suspend fun getSupport() = SupportResponse("e@e")
            override suspend fun getFaqs(): FaqListResponse {
                faqCalls++
                return if (faqCalls == 1) {
                    gate.await(); FaqListResponse(emptyList()) // gen1: 느리게 빈 목록
                } else {
                    FaqListResponse(listOf(faq(1))) // gen2: 즉시 1건
                }
            }
        }
        val vm = SupportViewModel(api)

        val first = launch { vm.refresh() } // gen1: FAQ gate 대기
        while (faqCalls < 1) yield()

        vm.refresh() // gen2: 즉시 완료 → faqs=[1], loading=false
        assertEquals(1, vm.faqs.size)
        assertFalse(vm.loading)

        gate.complete(Unit); first.join() // gen1 완료(빈 목록) → 최신 아님 → 덮지 않음
        assertEquals(1, vm.faqs.size)      // gen2 결과 유지
        assertFalse(vm.faqsError)
        assertFalse(vm.loading)            // gen1 이 loading 을 다시 건드리지 않음
    }

    @Test
    fun email_failure_keeps_default_but_faqs_still_load() = runTest {
        val vm = SupportViewModel(
            FakeApi(
                support = { throw RuntimeException("boom") },
                faqs = { FaqListResponse(listOf(faq(1))) },
            ),
        )
        vm.load(); advanceUntilIdle()

        assertEquals(SupportViewModel.DEFAULT_EMAIL, vm.email) // 기본 문의처 유지
        assertFalse(vm.faqsError)
        assertEquals(1, vm.faqs.size)                          // FAQ 는 정상 로드
    }
}
