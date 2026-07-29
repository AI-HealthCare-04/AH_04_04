package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 측정 중 폰 배치 안내(#232) 노출 경계 검증 — 검출기 무수술 UX 완화.
 *
 * 경과 시간은 흐르는데 걸음이 계속 0(보행 확정 게이트 미개방)이 임계 시간 이상 지속될 때만 뜬다.
 * 순수 함수(shouldShowPlacementHint)라 화면/기기 없이 경계를 단위 검증한다.
 */
class PlacementHintPolicyTest {

    @Test
    fun `0보로 임계 시간 이상 지나면 배치 안내를 노출한다`() {
        assertTrue(shouldShowPlacementHint(elapsedSec = PLACEMENT_HINT_AFTER_SEC, steps = 0))
        assertTrue(shouldShowPlacementHint(elapsedSec = PLACEMENT_HINT_AFTER_SEC + 30, steps = 0))
    }

    @Test
    fun `임계 시간 전에는 노출하지 않는다`() {
        assertFalse(shouldShowPlacementHint(elapsedSec = 0, steps = 0))
        assertFalse(shouldShowPlacementHint(elapsedSec = PLACEMENT_HINT_AFTER_SEC - 1, steps = 0))
    }

    @Test
    fun `걸음이 하나라도 잡히면 노출하지 않는다`() {
        // 게이트가 열려 걸음이 오르면(정상 보행) 안내는 불필요.
        assertFalse(shouldShowPlacementHint(elapsedSec = PLACEMENT_HINT_AFTER_SEC, steps = 1))
        assertFalse(shouldShowPlacementHint(elapsedSec = PLACEMENT_HINT_AFTER_SEC + 100, steps = 10))
    }
}
