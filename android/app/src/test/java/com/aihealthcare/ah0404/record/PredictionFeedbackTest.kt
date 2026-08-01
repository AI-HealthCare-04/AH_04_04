package com.aihealthcare.ah0404.record

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 체감 피드백(#357) 노출 정책 계약: **prediction_id 당 1회**.
 * 응답·건너뛰기 모두 로컬에 기록되어 같은 예측에는 재노출하지 않는다(시니어 피로·중복 응답 방지).
 * 서버 UNIQUE·멱등 PUT 이 데이터 정합을 지키므로, 재설치로 기록이 사라져도 오염은 없다.
 */
@RunWith(RobolectricTestRunner::class)
class PredictionFeedbackTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("prediction_feedback", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun `기록 전에는 미노출 처리되지 않고, 기록 후에는 같은 예측이 처리됨으로 판별된다`() {
        assertFalse(PredictionFeedbackExposure.isHandled(context, 42))

        PredictionFeedbackExposure.markHandled(context, 42)

        assertTrue(PredictionFeedbackExposure.isHandled(context, 42))
        assertFalse("다른 예측은 영향받지 않는다", PredictionFeedbackExposure.isHandled(context, 43))
    }

    @Test
    fun `기록 상한을 넘어도 최근 기록은 유지된다`() {
        // 상한(100) 초과분은 임의 폐기 — 과거 예측은 노출 후보가 아니라서 무해하다는 계약만 고정한다:
        // 초과 저장이 크래시 없이 동작하고, 방금 기록한 id 는 판별 가능해야 한다.
        repeat(150) { PredictionFeedbackExposure.markHandled(context, it) }
        assertTrue(PredictionFeedbackExposure.isHandled(context, 149))
    }
}
