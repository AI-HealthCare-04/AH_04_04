package com.aihealthcare.ah0404.record

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

// =====================================================================================
// 예측 결과 체감 피드백 카드(#357 옵션 B). 점수 카드 바로 아래, **새 예측(prediction_id)당 1회**만 묻는다.
//   - 응답은 주관적 체감 신호로 수집된다(서버 계약 PUT /risk-predictions/{id}/feedback, 멱등).
//   - 건너뛰기는 항상 가능하고, 응답·건너뛰기 모두 같은 예측에는 재노출하지 않는다(로컬 기록).
//   - 문구는 이슈 #357 확정안(지영 리뷰 §3) 그대로.
// =====================================================================================

/**
 * prediction_id 별 '이미 물었다' 로컬 기록. 서버 UNIQUE 가 중복 저장을 막지만,
 * 노출 자체를 줄이는 것(시니어 피로·중복 응답 방지)은 앱의 몫이라 여기서 관리한다.
 * 재설치 시 기록이 사라져 최신 예측에 한 번 더 물을 수 있는데, 서버 멱등 계약이라 데이터는 오염되지 않는다.
 */
internal object PredictionFeedbackExposure {
    private const val PREFS = "prediction_feedback"
    private const val KEY_HANDLED = "handled_prediction_ids"

    // StringSet 상한 — 카드는 최신 예측에만 붙으므로 실제로는 수십 건이면 충분하다. 초과분은 임의 폐기해도
    //   과거 예측은 더 이상 노출 후보가 아니라 무해하다.
    private const val MAX_IDS = 100

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isHandled(context: Context, predictionId: Int): Boolean =
        prefs(context).getStringSet(KEY_HANDLED, emptySet())!!.contains(predictionId.toString())

    fun markHandled(context: Context, predictionId: Int) {
        val current = prefs(context).getStringSet(KEY_HANDLED, emptySet())!!.toMutableSet()
        current += predictionId.toString()
        val capped = if (current.size > MAX_IDS) current.drop(current.size - MAX_IDS).toSet() else current
        prefs(context).edit().putStringSet(KEY_HANDLED, capped).apply()
    }
}

/**
 * 체감 피드백 카드. [onSubmit] 은 (predictionId, response) — response 는 서버 계약값
 * similar/unsure/different. 이미 응답·건너뛰기한 예측이면 아무것도 그리지 않는다.
 */
@Composable
internal fun PredictionFeedbackCard(predictionId: Int, onSubmit: (Int, String) -> Unit) {
    val context = LocalContext.current
    var handled by remember(predictionId) {
        mutableStateOf(PredictionFeedbackExposure.isHandled(context, predictionId))
    }
    var thanked by remember(predictionId) { mutableStateOf(false) }
    if (handled && !thanked) return

    fun answer(response: String) {
        PredictionFeedbackExposure.markHandled(context, predictionId)
        handled = true
        thanked = true
        onSubmit(predictionId, response)
    }

    AigoCard {
        if (thanked) {
            Text(
                "의견 감사해요. 서비스 개선에 소중히 쓸게요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            return@AigoCard
        }
        Column {
            Text(
                "이 결과가 평소 느끼는 몸 상태와 비슷한가요?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Dimens.Space4))
            Text(
                "응답은 서비스 개선에 활용되며, 진단 결과로 사용되지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.Space12))
            AigoSecondaryButton(text = "비슷해요", onClick = { answer("similar") })
            Spacer(Modifier.height(Dimens.Space8))
            AigoSecondaryButton(text = "잘 모르겠어요", onClick = { answer("unsure") })
            Spacer(Modifier.height(Dimens.Space8))
            AigoSecondaryButton(text = "다르게 느껴져요", onClick = { answer("different") })
            // 건너뛰기(항상 가능) — 같은 예측에는 다시 묻지 않되 서버 전송은 없다.
            TextButton(
                onClick = {
                    PredictionFeedbackExposure.markHandled(context, predictionId)
                    handled = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("다음에 할게요", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
