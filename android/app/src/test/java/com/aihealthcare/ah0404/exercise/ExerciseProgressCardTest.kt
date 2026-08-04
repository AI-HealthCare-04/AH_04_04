package com.aihealthcare.ah0404.exercise

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 운동 메인 상단 진행 히어로 카드의 '분' 표시 회귀 테스트(리뷰 #280 재발 방지).
 *
 *  카드가 [formatExerciseMinutes] 를 거치지 않고 직접 반올림하면(`String.format("%.1f")` 등)
 *  목표 미달인 9.9x 분이 "10분"으로 보여, 같은 카드 안의 "조금만 더 하면" 안내와 모순된다.
 *  포맷 함수만 검증하면 카드가 그 함수를 안 쓰도록 바뀌는 회귀를 못 잡으므로, 카드를 실제로 렌더해서 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ExerciseProgressCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `목표 미달 9_96분은 10분으로 올림되지 않는다`() {
        compose.setContent {
            MyApplicationTheme {
                ExerciseProgressCard(minutes = 9.96f, goalReached = false)
            }
        }

        // 버림되어 9.9 로 보여야 한다.
        compose.onNodeWithText("오늘 운동 9.9분 했어요").assertIsDisplayed()
        compose.onNodeWithText("목표 10분 중 9.9분").assertIsDisplayed()
        // 미달인데 목표치처럼 보이는 문구가 있으면 안 된다.
        compose.onNodeWithText("오늘 운동 10분 했어요").assertDoesNotExist()
        // 미달이므로 격려 문구가 함께 떠 있어야 모순이 없다.
        compose.onNodeWithText("조금만 더 하면 오늘 목표를 채울 수 있어요.").assertIsDisplayed()
    }

    @Test
    fun `목표 달성이면 반올림해 10분으로 보여주고 달성 문구를 띄운다`() {
        compose.setContent {
            MyApplicationTheme {
                ExerciseProgressCard(minutes = 10.02f, goalReached = true)
            }
        }

        compose.onNodeWithText("오늘 운동 10분 했어요").assertIsDisplayed()
        compose.onNodeWithText("오늘 목표를 채웠어요 🎉").assertIsDisplayed()
    }
}