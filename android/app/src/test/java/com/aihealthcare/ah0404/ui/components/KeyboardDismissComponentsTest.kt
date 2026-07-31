package com.aihealthcare.ah0404.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class KeyboardDismissComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionStepperAndButtonClearTextFieldFocus() {
        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf<String?>(null) }
            var days by remember { mutableIntStateOf(1) }

            MyApplicationTheme {
                Column {
                    AigoTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "입력",
                        modifier = Modifier.testTag("input"),
                    )
                    AigoSegmentedSelector(
                        options = listOf(SegmentOption("selected", "선택")),
                        selected = selected,
                        onSelect = { selected = it },
                    )
                    AigoDayStepper(
                        value = days,
                        onValueChange = { days = it },
                        max = 2,
                    )
                    AigoPrimaryButton(text = "저장", onClick = {})
                }
            }
        }

        val input = composeRule.onNodeWithTag("input")

        input.performClick().assertIsFocused()
        composeRule.onNodeWithText("선택").performClick()
        input.assertIsNotFocused()

        input.performClick().assertIsFocused()
        composeRule.onNodeWithContentDescription("일 늘리기").performClick()
        input.assertIsNotFocused()

        input.performClick().assertIsFocused()
        composeRule.onNodeWithText("저장").performClick()
        input.assertIsNotFocused()
    }
}
