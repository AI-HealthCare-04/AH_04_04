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
    fun meal_routes_to_coming_soon() {
        // 식사는 아직 수행 화면이 없다 → '준비 중'. 걷기 경로로 가면 안 된다.
        assertEquals(MissionDestination.COMING_SOON, missionDestination("meal"))
    }

    @Test
    fun unknown_type_falls_back_to_coming_soon_not_walking() {
        // 미지의/오타 유형이 걷기 측정(=기록 경로)으로 새지 않도록 안전 기본값은 COMING_SOON.
        assertEquals(MissionDestination.COMING_SOON, missionDestination("unknown_future_type"))
        assertEquals(MissionDestination.COMING_SOON, missionDestination(""))
    }
}
