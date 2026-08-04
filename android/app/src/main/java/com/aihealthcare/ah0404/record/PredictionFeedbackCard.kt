package com.aihealthcare.ah0404.record

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.SentimentNeutral
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.SelectionRowShape

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
            FEEDBACK_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) Spacer(Modifier.height(Dimens.Space8))
                FeedbackOptionRow(option) { answer(option.response) }
            }
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

/** 응답 한 줄 — 표정 아이콘 + 문구, [response] 는 서버 계약값(similar/unsure/different). */
private data class FeedbackOption(val label: String, val icon: ImageVector, val response: String)

// 문구는 이슈 #357 확정안 그대로. 표정은 문구의 방향(긍정/중립/부정)을 눈으로 먼저 잡아 주는 보조 장치다.
private val FEEDBACK_OPTIONS = listOf(
    FeedbackOption("비슷해요", Icons.Outlined.SentimentSatisfiedAlt, "similar"),
    FeedbackOption("잘 모르겠어요", Icons.Outlined.SentimentNeutral, "unsure"),
    FeedbackOption("다르게 느껴져요", Icons.Outlined.SentimentDissatisfied, "different"),
)

/**
 * 선택 행(핸드오프 §5). 세 응답을 꽉 찬 보조버튼으로 쌓으면 "눌러야 할 CTA 셋"으로 읽혀,
 * 설문이 아니라 강한 액션처럼 보였다 → **흰 바탕 + 연회색 테두리의 선택 행**으로 낮춘다.
 *
 * 누르는 즉시 응답이 전송되고 카드가 감사 문구로 바뀌므로(기존 #357 동작) 선택 상태는 따로 두지 않는다.
 * 행 전체가 터치 대상이고 높이는 최소 터치 영역 이상 — 아이콘만 노려 누르지 않게 한다.
 */
@Composable
private fun FeedbackOptionRow(option: FeedbackOption, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = SelectionRowShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(Dimens.HairlineBorder, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .padding(horizontal = Dimens.Space16, vertical = Dimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Icon(
                option.icon,
                contentDescription = null, // 장식용 — 의미는 옆 문구가 전달한다(§10).
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(option.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}
