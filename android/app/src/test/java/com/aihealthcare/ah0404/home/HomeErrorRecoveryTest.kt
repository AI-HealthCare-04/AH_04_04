package com.aihealthcare.ah0404.home

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aihealthcare.ah0404.network.HomeApi
import com.aihealthcare.ah0404.network.HomeResponse
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 홈 조회가 실패했을 때 **사용자가 스스로 빠져나올 수 있는지** 고정한다(PR #433 리뷰 P1).
 *
 *  제보 상황: 홈이 error 로 굳으면 `다시 시도` 는 같은 요청을 반복할 뿐이라, 실패가 지속되는
 *  원인에서는 앱을 지우는 것 외에 나갈 방법이 없었다.
 *
 *  ⚠️ 1차 수정은 화면 안에서 `SessionStore.clearAuthentication()` 을 직접 불렀는데, 그러면
 *  토큰·디스크 세션만 지워지고 `MainActivity` 의 `sessionRevision` 이 안 올라간다. 라우팅의
 *  `tokenStatus` 는 `remember(sessionRevision)` 에 캐시돼 있어 **버튼을 눌러도 화면이 그대로**였다
 *  — 고착을 푸는 버튼이 그 자체로 고착돼 있었다. 그래서 호스트 콜백으로 올렸고, 이 테스트가
 *  "재로그인은 반드시 호스트로 위임된다"를 못박는다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HomeErrorRecoveryTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 항상 실패하는 API — 홈을 error 상태로 만든다(첫 조회 실패라 ui 는 null 로 남는다). */
    private class FailingHomeApi : HomeApi {
        override suspend fun getHome(): HomeResponse = throw IllegalStateException("조회 실패")
    }

    @Test
    fun 홈_조회가_실패하면_재로그인_경로가_보인다() {
        // composable 밖에서 만든다 — 안에서 생성하면 재구성마다 새 VM 이 생긴다(lint 규칙).
        val vm = HomeViewModel(FailingHomeApi())
        composeRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    onGoMissions = {}, onOpenSettings = {}, onOpenRecords = {},
                    onRelogin = {},
                    vm = vm,
                )
            }
        }
        composeRule.onNodeWithText("홈 정보를 불러오지 못했어요.").assertExists()
        composeRule.onNodeWithText("다시 시도").assertExists()
        // 탈출 경로가 없으면 사용자는 앱을 지우는 수밖에 없다 — 이 버튼의 존재가 수정의 핵심이다.
        composeRule.onNodeWithText("다시 로그인").assertExists()
    }

    @Test
    fun 재로그인은_호스트_콜백으로_위임된다() {
        val vm = HomeViewModel(FailingHomeApi())
        var reloginRequested = false
        composeRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    onGoMissions = {}, onOpenSettings = {}, onOpenRecords = {},
                    // 호스트는 여기서 signOut { sessionRevision++ } 를 실행한다(MainActivity 의 onLogout).
                    //   화면이 SessionStore 를 직접 만지면 이 콜백이 안 불리고 라우팅도 안 바뀐다.
                    onRelogin = { reloginRequested = true },
                    vm = vm,
                )
            }
        }
        assertFalse("누르기 전에는 호출되지 않는다", reloginRequested)

        composeRule.onNodeWithText("다시 로그인").performClick()

        assertTrue(
            "재로그인은 호스트로 위임돼야 라우팅(sessionRevision)이 갱신된다",
            reloginRequested,
        )
    }
}
