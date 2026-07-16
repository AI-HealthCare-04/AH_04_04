package com.aihealthcare.ah0404.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RoutineLoader.validateStructure 의 구조 규칙 검증(Context 불필요한 순수 로직).
 * 팀원이 새 루틴 JSON을 추가할 때 흔한 실수를 조기에 잡는지 확인.
 */
class RoutineValidationTest {

    private fun step(
        type: StepType = StepType.IMAGE,
        sec: Int = 10,
        name: String = "동작",
        asset: String? = "img",
        mode: StepMode = StepMode.TIMER,
        count: Int? = null,
    ) = Step(type = type, sec = sec, name = name, asset = asset, mode = mode, count = count)

    private fun routine(steps: List<Step>) =
        Routine(id = "r", title = "루틴", subtitle = "", bgm = "bgm", totalSec = 10, steps = steps)

    @Test
    fun `유효한 루틴은 오류가 없다`() {
        val r = routine(
            listOf(
                Step(type = StepType.INTRO, sec = 5, name = "시작", mode = StepMode.NONE),
                step(type = StepType.VIDEO, sec = 30, asset = "march", mode = StepMode.TIMER),
                step(type = StepType.VIDEO, sec = 20, asset = "arm", mode = StepMode.COUNT, count = 10),
                step(type = StepType.IMAGE, sec = 10, asset = "neck_1", mode = StepMode.TIMER),
            ),
        )
        assertTrue(RoutineLoader.validateStructure(r).isEmpty())
    }

    @Test
    fun `steps가 비면 오류`() {
        val errors = RoutineLoader.validateStructure(routine(emptyList()))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("steps가 비어"))
    }

    @Test
    fun `sec가 0 이하이면 오류`() {
        assertTrue(RoutineLoader.validateStructure(routine(listOf(step(sec = 0)))).any { it.contains("sec") })
        assertTrue(RoutineLoader.validateStructure(routine(listOf(step(sec = -3)))).any { it.contains("sec") })
    }

    @Test
    fun `count 모드인데 count가 없으면 오류`() {
        val errors = RoutineLoader.validateStructure(
            routine(listOf(step(mode = StepMode.COUNT, count = null))),
        )
        assertTrue(errors.any { it.contains("count") })
    }

    @Test
    fun `count 모드인데 count가 0 이하이면 오류`() {
        assertTrue(
            RoutineLoader.validateStructure(routine(listOf(step(mode = StepMode.COUNT, count = 0))))
                .any { it.contains("count") },
        )
    }

    @Test
    fun `count 모드가 아니면 count 없어도 정상`() {
        val r = routine(listOf(step(mode = StepMode.TIMER, count = null)))
        assertTrue(RoutineLoader.validateStructure(r).isEmpty())
    }

    @Test
    fun `video 또는 image인데 asset이 없으면 오류`() {
        assertTrue(
            RoutineLoader.validateStructure(routine(listOf(step(type = StepType.VIDEO, asset = null))))
                .any { it.contains("asset") },
        )
        assertTrue(
            RoutineLoader.validateStructure(routine(listOf(step(type = StepType.IMAGE, asset = null))))
                .any { it.contains("asset") },
        )
    }

    @Test
    fun `intro notice outro는 asset이 없어도 정상`() {
        val r = routine(
            listOf(
                Step(type = StepType.INTRO, sec = 5, name = "시작", asset = null, mode = StepMode.NONE),
                Step(type = StepType.NOTICE, sec = 3, name = "안내", asset = null, mode = StepMode.NONE),
                Step(type = StepType.OUTRO, sec = 5, name = "끝", asset = null, mode = StepMode.NONE),
            ),
        )
        assertTrue(RoutineLoader.validateStructure(r).isEmpty())
    }

    @Test
    fun `여러 오류는 모두 수집된다`() {
        val r = routine(
            listOf(
                step(sec = 0, mode = StepMode.COUNT, count = null),           // sec + count 2건
                step(type = StepType.VIDEO, sec = -1, asset = null),          // sec + asset 2건
            ),
        )
        val errors = RoutineLoader.validateStructure(r)
        assertEquals(4, errors.size)
    }
}
