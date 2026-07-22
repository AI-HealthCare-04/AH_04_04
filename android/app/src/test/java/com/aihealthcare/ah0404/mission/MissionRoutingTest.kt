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
    fun non_walking_types_route_to_coming_soon() {
        // 운동·식사·게임은 아직 수행 화면이 없다 → '준비 중'. 걷기 경로로 가면 안 된다.
        listOf("exercise", "meal", "game").forEach { type ->
            assertEquals(
                "미션 유형 '$type' 은 준비 중으로 가야 한다",
                MissionDestination.COMING_SOON,
                missionDestination(type),
            )
        }
    }

    @Test
    fun unknown_type_falls_back_to_coming_soon_not_walking() {
        // 미지의/오타 유형이 걷기 측정(=기록 경로)으로 새지 않도록 안전 기본값은 COMING_SOON.
        assertEquals(MissionDestination.COMING_SOON, missionDestination("unknown_future_type"))
        assertEquals(MissionDestination.COMING_SOON, missionDestination(""))
    }
}
