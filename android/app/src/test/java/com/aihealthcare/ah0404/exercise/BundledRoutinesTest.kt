package com.aihealthcare.ah0404.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 번들 루틴 목록(BUNDLED_ROUTINES) 검증.
 *
 * 탭 경로(stage→file)와 **목록 실패(오프라인) 폴백**이 모두 이 단일 출처에서 파생된다.
 * 마무리(cooldown)가 빠져 오프라인에서 진입 못 하던 회귀를 막는다(지영 리뷰 #240):
 * BUNDLED_ROUTINES 에 cooldown 이 있으면 RoutineFallback 이 그 시작 버튼을 렌더한다.
 */
class BundledRoutinesTest {

    @Test
    fun `번들 루틴에 몸풀기와 마무리가 모두 있다`() {
        val stages = BUNDLED_ROUTINES.map { it.stage }
        assertTrue("몸풀기 번들 누락", stages.contains("warmup"))
        assertTrue("마무리 번들 누락 — 오프라인 폴백에서 진입 불가 회귀", stages.contains("cooldown"))
    }

    @Test
    fun `각 번들 루틴은 대응 JSON 파일을 가리킨다`() {
        val byStage = BUNDLED_ROUTINES.associateBy { it.stage }
        assertEquals("warmup_common.json", byStage["warmup"]?.file)
        assertEquals("cooldown_common.json", byStage["cooldown"]?.file)
    }

    @Test
    fun `모든 번들 루틴에 포스터가 있다`() {
        // 포스터가 없으면 VideoArea 가 조용히 이모지 안내로 떨어져, 네 탭 중 둘만 포스터인 상태로 되돌아간다.
        BUNDLED_ROUTINES.forEach {
            assertNotNull("포스터 누락 — 이모지 안내로 되돌아감: ${it.stage}", exercisePosterRes(it.stage))
        }
    }

    @Test
    fun `모든 번들 루틴은 라벨과 json 파일명을 갖춘다`() {
        BUNDLED_ROUTINES.forEach {
            assertTrue("라벨 비어있음: ${it.stage}", it.label.isNotBlank())
            assertTrue("json 아님: ${it.file}", it.file.endsWith(".json"))
        }
    }
}
