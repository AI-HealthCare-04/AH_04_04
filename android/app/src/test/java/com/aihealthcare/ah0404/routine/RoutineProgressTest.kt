package com.aihealthcare.ah0404.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 루틴 진행 표시(#335) 순수 로직: 동작 '종류' 그룹핑·현재 순번·소단계 위치·진행바 비율. */
class RoutineProgressTest {

    // 실제 warmup_common.json 구조를 그대로 모사(23단계 → 9종류). 안내(intro/notice/outro)=mode NONE.
    private fun ex(name: String, mode: StepMode = StepMode.TIMER) = Step(StepType.IMAGE, 10, name, mode = mode)
    private fun guide(type: StepType, name: String) = Step(type, 5, name) // mode 기본 NONE

    private val warmup: List<Step> = listOf(
        guide(StepType.INTRO, "몸풀기 운동"),      // 0
        ex("제자리 걷기"),                         // 1
        ex("팔 앞뒤 흔들기", StepMode.COUNT),      // 2
        ex("목 늘리기"), ex("목 늘리기"), ex("목 늘리기"), // 3,4,5
        ex("목 늘리기"), ex("목 늘리기"), ex("목 늘리기"), // 6,7,8
        ex("어깨 스트레칭"), ex("어깨 스트레칭"),  // 9,10
        ex("옆구리 스트레칭"), ex("옆구리 스트레칭"), // 11,12
        ex("몸통 회전", StepMode.COUNT),           // 13
        guide(StepType.NOTICE, "의자에 앉아주세요"), // 14
        ex("발목 돌리기", StepMode.COUNT),         // 15
        ex("발목 돌리기 (반대 발)", StepMode.COUNT), // 16
        ex("발가락 당기기", StepMode.COUNT),       // 17
        ex("발가락 당기기 (반대 발)", StepMode.COUNT), // 18
        guide(StepType.NOTICE, "천천히 일어나주세요"), // 19
        ex("종아리 스트레칭"), ex("종아리 스트레칭"), // 20,21
        guide(StepType.OUTRO, "수고하셨어요"),     // 22
    )

    @Test
    fun `기본 동작명 - 좌우·방향 변형 접미 제거`() {
        assertEquals("발목 돌리기", exerciseBaseName("발목 돌리기 (반대 발)"))
        assertEquals("어깨 스트레칭", exerciseBaseName("어깨 스트레칭 (오른쪽)"))
        assertEquals("목 늘리기", exerciseBaseName("목 늘리기")) // 접미 없으면 그대로
    }

    @Test
    fun `동작 종류 그룹핑 - 몸풀기 23단계는 9종류`() {
        val groups = exerciseGroups(warmup)
        assertEquals("좌우·방향 변형과 안내를 제외하면 동작 종류는 9개", 9, groups.size)
        assertEquals("목 늘리기 6단계가 한 종류로 묶임", listOf(3, 4, 5, 6, 7, 8), groups[2])
        assertEquals("발목 돌리기 + 반대 발이 한 종류", listOf(15, 16), groups[6])
        // 안내 단계(0·14·19·22)는 어느 그룹에도 없다.
        assertEquals(0, groups.count { 0 in it || 14 in it || 19 in it || 22 in it })
    }

    @Test
    fun `진행 라벨 - 종류 순번과 소단계 위치`() {
        // 목 늘리기 첫 단계(3) = 9개 중 3번째, 소단계 1/6
        routineProgressLabel(warmup, 3).let {
            assertEquals(9, it.groupTotal); assertEquals(3, it.groupIndex); assertEquals(1 to 6, it.withinGroup)
        }
        // 목 늘리기 마지막 단계(8) = 6/6
        assertEquals(6 to 6, routineProgressLabel(warmup, 8).withinGroup)
        // 발목 돌리기(15) = 7번째, 1/2
        routineProgressLabel(warmup, 15).let {
            assertEquals(7, it.groupIndex); assertEquals(1 to 2, it.withinGroup)
        }
        // 제자리 걷기(1) = 1번째, 소단계 1개라 withinGroup null
        routineProgressLabel(warmup, 1).let {
            assertEquals(1, it.groupIndex); assertNull(it.withinGroup)
        }
        // 안내 단계(intro 0, notice 14, outro 22)는 groupIndex null(카운트 제외)
        assertNull(routineProgressLabel(warmup, 0).groupIndex)
        assertNull(routineProgressLabel(warmup, 14).groupIndex)
        assertNull(routineProgressLabel(warmup, 22).groupIndex)
        // 안내 단계라도 groupTotal 은 9 유지
        assertEquals(9, routineProgressLabel(warmup, 14).groupTotal)
    }

    @Test
    fun `진행 캡션 - 문구와 안내 단계 미표시`() {
        assertEquals("9개 동작 중 3번째 (1/6)", routineProgressCaption(routineProgressLabel(warmup, 3)))
        assertEquals("9개 동작 중 1번째", routineProgressCaption(routineProgressLabel(warmup, 1)))
        assertNull("안내 단계는 캡션 없음", routineProgressCaption(routineProgressLabel(warmup, 14)))
    }

    @Test
    fun `진행바 비율 - 계획 시간 누적 기준`() {
        val two = listOf(ex("A"), ex("B")) // 각 10초, 총 20초
        assertEquals(0f, routineElapsedFraction(two, 0, 0L), 1e-4f)
        assertEquals(0.25f, routineElapsedFraction(two, 0, 5_000L), 1e-4f) // 첫 단계 절반
        assertEquals(0.5f, routineElapsedFraction(two, 1, 0L), 1e-4f) // 두 번째 단계 진입
        assertEquals(1f, routineElapsedFraction(two, 1, 10_000L), 1e-4f) // 끝
    }

    @Test
    fun `마무리 21단계는 8종류`() {
        // 실제 cooldown_common.json 구조 그대로(각 부위 2단계 + 목 늘리기 6단계 + 고양이낙타 + 호흡).
        val cooldown = listOf(
            guide(StepType.INTRO, "마무리 운동"),
            ex("종아리 늘리기"), ex("종아리 늘리기"),
            ex("뒤 허벅지 늘리기"), ex("뒤 허벅지 늘리기"),
            ex("앞 허벅지 늘리기"), ex("앞 허벅지 늘리기"),
            ex("고관절 늘리기"), ex("고관절 늘리기"),
            ex("안 허벅지 늘리기"), ex("안 허벅지 늘리기"),
            guide(StepType.NOTICE, "의자에 앉아주세요"),
            Step(StepType.IMAGE_TOGGLE, 10, "고양이 낙타 스트레칭", mode = StepMode.COUNT),
            ex("목 늘리기"), ex("목 늘리기"), ex("목 늘리기"),
            ex("목 늘리기"), ex("목 늘리기"), ex("목 늘리기"),
            ex("호흡 정리", StepMode.COUNT),
            guide(StepType.OUTRO, "수고하셨어요"),
        )
        assertEquals(8, exerciseGroups(cooldown).size)
    }
}
