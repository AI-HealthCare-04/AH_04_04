package com.aihealthcare.ah0404.settings

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.FaqItem
import com.aihealthcare.ah0404.network.SupportApi
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 고객센터(_16) 상태 + 백엔드 배선(GET /support, GET /support/faqs, #74).
 *
 *  이메일과 FAQ 를 **독립적으로** 조회한다: FAQ 조회가 실패해도 문의 이메일은 보여야 하므로,
 *  이메일은 실패 시 기본값을 유지하고 FAQ 는 별도 오류 상태로 표시한다.
 *  진입마다 재조회 + generation 가드(겹친 조회 시 최신만 commit).
 */
class SupportViewModel(
    private val api: SupportApi = retrofit.create(SupportApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var email by mutableStateOf(DEFAULT_EMAIL); private set
    var faqs by mutableStateOf<List<FaqItem>>(emptyList()); private set
    var faqsError by mutableStateOf(false); private set

    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val gen = ++generation
        loading = true
        faqsError = false
        coroutineScope {
            val supportCall = async { safeCall { api.getSupport() } }
            val faqsCall = async { safeCall { api.getFaqs() } }
            val supportRes = supportCall.await()
            val faqsRes = faqsCall.await()
            // 최신 요청이 아니면 결과·완료 상태 모두 갱신하지 않는다(리뷰 #77: 오래된 refresh 가
            //   진행 중인 최신 요청의 loading 을 꺼서 빈 상태로 보이던 문제 방지). 최신 refresh 가 정리한다.
            if (gen != generation) return@coroutineScope
            // 이메일 실패 시엔 기본값 유지(문의 수단은 항상 노출).
            supportRes
                .onSuccess { email = it.email }
                .onFailure { Log.w(TAG, "고객센터 이메일 조회 실패: ${it.message}") }
            faqsRes
                .onSuccess { faqs = it.faqs.sortedBy { f -> f.faqId } }
                .onFailure { faqsError = true; Log.w(TAG, "FAQ 조회 실패: ${it.message}") }
            loaded = true
            loading = false
        }
    }

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        const val TAG = "Support"

        /** 서버 이메일 조회 실패 시에도 문의할 수 있도록 두는 기본 문의처. */
        const val DEFAULT_EMAIL = "support@aigo.example"
    }
}
