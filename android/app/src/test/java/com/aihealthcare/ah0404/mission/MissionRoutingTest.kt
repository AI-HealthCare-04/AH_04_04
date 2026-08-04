package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.MealTodayLog
import com.aihealthcare.ah0404.network.Mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 미션 유형 → 목적지 라우팅 규칙(#93) 검증.
 * 특히 **걷기 외 유형이 걷기 경로로 새지 않음**(기록 이중 경로 방지)을 못박는다.
 */
class MissionRoutingTest {

    @Test
    fun walking_routes_to_walking_screen() {
        assertEquals(MissionDestination.WALKING, missionDestination("walking"))
    }

    @Test
    fun exercise_routes_to_video_screen_like_home() {
        // 운동은 홈의 '영상 따라 운동하기'와 같은 목적지여야 한다 — 미션 탭만 '준비 중'으로
        //   막히면 같은 미션이 진입 경로에 따라 되기도/안 되기도 한다(#162).
        assertEquals(MissionDestination.EXERCISE_VIDEOS, missionDestination("exercise"))
    }

    @Test
    fun game_routes_to_mini_game_screen() {
        // 게임은 미니게임 영상 화면으로 연결(#93). 걷기 경로로 가면 안 된다.
        assertEquals(MissionDestination.MINI_GAME, missionDestination("game"))
    }

    @Test
    fun meal_routes_to_protein_challenge_screen() {
        // 식사는 단백질 식사 기록 화면으로 연결. 걷기 경로로 가면 안 된다.
        assertEquals(MissionDestination.PROTEIN_MEAL, missionDestination("meal"))
    }

    @Test
    fun unknown_type_falls_back_to_coming_soon_not_walking() {
        // 미지의/오타 유형이 걷기 측정(=기록 경로)으로 새지 않도록 안전 기본값은 COMING_SOON.
        assertEquals(MissionDestination.COMING_SOON, missionDestination("unknown_future_type"))
        assertEquals(MissionDestination.COMING_SOON, missionDestination(""))
    }

    // ── 카드 CTA 문구(리뷰 #225 P2): 실제 수행/기록 화면이 있는 유형은 '준비 중'으로 표시하지 않는다 ──

    @Test
    fun `걷기 카드는 측정 시작을 안내한다`() {
        assertEquals("눌러서 측정 시작 →", missionCtaLabel("walking"))
    }

    @Test
    fun `식사 카드는 준비 중이 아니라 기록하기를 안내한다`() {
        assertEquals("눌러서 기록하기 →", missionCtaLabel("meal"))
    }

    @Test
    fun `운동 영상과 게임도 실제 목적지 CTA 를 안내한다(준비 중 아님)`() {
        // #219 운동 영상·#220 미니게임은 실화면이 있다 — '준비 중' 회귀 방지(리뷰 #225 2차).
        assertEquals("눌러서 운동 영상 보기 →", missionCtaLabel("exercise"))
        assertEquals("눌러서 게임 하기 →", missionCtaLabel("game"))
    }

    @Test
    fun `수행 화면이 없는 미지의 유형만 준비 중을 안내한다`() {
        assertEquals("준비 중 · 눌러서 보기 →", missionCtaLabel("unknown_future_type"))
        assertEquals("준비 중 · 눌러서 보기 →", missionCtaLabel(""))
    }

    @Test
    fun `실화면이 있는 유형은 CTA 를 강조하고 준비 중만 강조하지 않는다`() {
        assertEquals(true, missionCtaHighlighted("walking"))
        assertEquals(true, missionCtaHighlighted("meal"))
        assertEquals(true, missionCtaHighlighted("exercise"))
        assertEquals(true, missionCtaHighlighted("game"))
        assertEquals(false, missionCtaHighlighted("unknown_future_type"))
    }

    // ── #304 요청 4: 단백질 미션 숨김 사유 문구 ──────────────────────────

    @Test
    fun `신장질환·투석이면 신장 사유를 안내한다`() {
        assertEquals(true, proteinHiddenReason("kidney_disease", "none")?.contains("신장"))
        assertEquals(true, proteinHiddenReason("dialysis", "unknown")?.contains("신장"))
    }

    @Test
    fun `신장이 없고 단백질 제한이면 제한 사유를 안내한다`() {
        assertEquals(true, proteinHiddenReason("none", "restricted")?.contains("단백질"))
    }

    @Test
    fun `상태 미상이면 내 정보 입력을 안내한다`() {
        assertEquals(true, proteinHiddenReason("unknown", "none")?.contains("내 정보"))
        assertEquals(true, proteinHiddenReason("none", "unknown")?.contains("내 정보"))
    }

    @Test
    fun `둘 다 none 이면 추측 안내를 하지 않는다`() {
        assertEquals(null, proteinHiddenReason("none", "none"))
    }

    // ── #346: 1회성 미션(식사·게임) '오늘 했음' 배지 ────────────────────

    private fun mission(
        type: String,
        todayLog: MealTodayLog? = null,
        todayDone: Boolean? = null,
    ) = Mission(
        missionTemplateId = 1,
        missionType = type,
        title = "m",
        description = null,
        level = "easy",
        targetValue = 1,
        targetUnit = "reps",
        requiresSafetyNotice = false,
        rewardPoints = 10,
        todayLog = todayLog,
        todayDone = todayDone,
    )

    @Test
    fun `식사 배지 - 달성·안먹었어요·미기록을 구분한다`() {
        assertEquals(
            "오늘 단백질 챙겼어요 🎉 · 2가지",
            missionTodayBadge(mission("meal", todayLog = MealTodayLog(eaten = listOf("a", "b"), loggedAt = "t"))),
        )
        assertEquals(
            "오늘은 안 드신 것으로 기록했어요",
            missionTodayBadge(mission("meal", todayLog = MealTodayLog(eaten = emptyList(), loggedAt = "t"))),
        )
        assertEquals(null, missionTodayBadge(mission("meal"))) // 미기록이면 표시 없음
    }

    @Test
    fun `게임 배지 - 오늘 완료만 표시하고 구버전 서버(null)는 무시한다`() {
        assertEquals("오늘 게임 했어요 🎉", missionTodayBadge(mission("game", todayDone = true)))
        assertEquals(null, missionTodayBadge(mission("game", todayDone = false)))
        assertEquals(null, missionTodayBadge(mission("game", todayDone = null)))
    }

    @Test
    fun `걷기·운동은 배지 대신 기존 진행바 담당(null)`() {
        assertEquals(null, missionTodayBadge(mission("walking")))
        assertEquals(null, missionTodayBadge(mission("exercise")))
    }
}
