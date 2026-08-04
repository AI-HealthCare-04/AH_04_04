package com.aihealthcare.ah0404.ui.components

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * compact 세그먼트(걷기 단위 토글)의 **클릭 영역**을 고정한다 — PR #413 리뷰 P1.
 *
 *  이전 구현은 48dp Box 안에 32dp 짜리 clickable Surface 를 넣어서, 시각적으로만 커 보이고
 *  실제로 누를 수 있는 곳은 32dp 알약뿐이었다. 시니어 대상 앱에서 이건 그냥 못 누르는 버튼이다.
 *  "시각 높이는 작게, 터치 영역은 48dp 이상"이 이 컴포넌트의 계약이라 회귀로 고정한다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SegmentedSelectorTouchTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setCompactSelector() {
        composeRule.setContent {
            MyApplicationTheme {
                AigoSegmentedSelector(
                    options = listOf(SegmentOption("m", "분"), SegmentOption("s", "걸음")),
                    selected = "m",
                    onSelect = {},
                    horizontal = true,
                    minHeight = 32.dp,
                    compact = true,
                )
            }
        }
    }

    @Test
    fun compact_segment_click_target_is_at_least_48dp_tall() {
        setCompactSelector()
        // 라벨이 속한 클릭 가능 노드(= onClick 이 붙은 Surface)의 높이를 잰다.
        val clickable = composeRule.onNodeWithText("걸음", useUnmergedTree = false)
            .fetchSemanticsNode()
        val heightDp = with(composeRule.density) { clickable.size.height.toDp() }
        assertTrue(
            "compact 세그먼트 터치 영역이 48dp 미만입니다: $heightDp",
            heightDp >= 48.dp,
        )
    }

    @Test
    fun non_compact_segment_keeps_its_own_min_height() {
        composeRule.setContent {
            MyApplicationTheme {
                AigoSegmentedSelector(
                    options = listOf(SegmentOption("a", "근육 건강"), SegmentOption("b", "미션 기록")),
                    selected = "a",
                    onSelect = {},
                    horizontal = true,
                    minHeight = 48.dp,
                )
            }
        }
        val node = composeRule.onNodeWithText("미션 기록").fetchSemanticsNode()
        val heightDp = with(composeRule.density) { node.size.height.toDp() }
        assertTrue("상단 탭 높이가 48dp 미만입니다: $heightDp", heightDp >= 48.dp)
    }
}
