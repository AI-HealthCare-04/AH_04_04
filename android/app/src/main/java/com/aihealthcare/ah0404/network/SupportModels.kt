package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 고객센터(_16) DTO — dev 백엔드 정본(GET /support, GET /support/faqs, #74) 기준.
 *  FAQ 는 정적 카탈로그(서버 관리) → 앱은 진입 시 1회 조회 후 화면에 그대로 표시. faq_id 순.
 */
@Serializable
data class SupportResponse(val email: String)

@Serializable
data class FaqItem(
    @SerialName("faq_id") val faqId: Int,
    val question: String,
    val answer: String,
)

@Serializable
data class FaqListResponse(val faqs: List<FaqItem> = emptyList())
