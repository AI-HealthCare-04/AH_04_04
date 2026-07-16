package com.aihealthcare.ah0404.settings

import com.aihealthcare.ah0404.network.FaqItem
import com.aihealthcare.ah0404.network.FaqListResponse
import com.aihealthcare.ah0404.network.SupportApi
import com.aihealthcare.ah0404.network.SupportResponse
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
