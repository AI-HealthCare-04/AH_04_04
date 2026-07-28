package com.aihealthcare.ah0404.mission

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
}
