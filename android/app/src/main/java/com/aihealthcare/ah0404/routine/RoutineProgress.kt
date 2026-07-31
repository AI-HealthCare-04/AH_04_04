package com.aihealthcare.ah0404.routine

// =====================================================================================
// 루틴 진행 표시(#335) — '몇 번째 / 총 몇 개'를 **동작 종류** 기준으로 센다.
//   단계 수(예: 몸풀기 23)를 그대로 쓰면 좌우·방향 변형이 각각 세어져 실제보다 부담스럽다. 그래서
//   ① 안내 단계(intro/notice/outro = mode NONE)는 진행 카운트에서 제외하고,
//   ② 운동 단계는 '기본 동작명'(좌우·방향 변형의 " (...)" 접미 제거) 기준 **연속 런**으로 묶어 한 종류로 센다.
//   예) 목 늘리기 6단계·발목 돌리기+반대 발 → 각 1종류. 몸풀기 23단계 → 9종류, 마무리 21단계 → 8종류.
// =====================================================================================

/** 동작명에서 좌우·방향 변형 접미(" (반대 발)", " (오른쪽)" 등)를 떼어낸 기본 동작명. */
internal fun exerciseBaseName(name: String): String =
    name.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").trim()

/**
 * 운동 단계를 '동작 종류'로 묶은 목록(각 원소 = 그 종류에 속한 step 인덱스들). 안내 단계는 어느 그룹에도 넣지 않되
 * 런을 끊지는 않는다(같은 동작이 안내로 잠깐 나뉘어도 한 종류로 유지). 표시/카운트용 순수 함수.
 */
internal fun exerciseGroups(steps: List<Step>): List<List<Int>> {
    val groups = mutableListOf<MutableList<Int>>()
    var prevBase: String? = null
    steps.forEachIndexed { i, s ->
        if (s.mode == StepMode.NONE) return@forEachIndexed // 안내 단계는 그룹에서 제외(런은 유지)
        val base = exerciseBaseName(s.name)
        if (groups.isEmpty() || base != prevBase) groups.add(mutableListOf(i)) else groups.last().add(i)
        prevBase = base
    }
    return groups
}

/** 현재 단계의 진행 라벨. groupIndex=null 이면 안내 단계(카운트 미표시, 진행바만 흐름). */
internal data class RoutineProgressLabel(
    val groupTotal: Int,          // 동작 종류 총 개수(예: 몸풀기 9)
    val groupIndex: Int?,         // 1-based 현재 종류 순번. null = 안내 단계
    val withinGroup: Pair<Int, Int>?, // (m, n): 종류 안 소단계 위치(좌우 반복 등). 소단계 1개면 null
)

internal fun routineProgressLabel(steps: List<Step>, stepIndex: Int): RoutineProgressLabel {
    val groups = exerciseGroups(steps)
    val gi = groups.indexOfFirst { stepIndex in it }
    if (gi < 0) return RoutineProgressLabel(groups.size, null, null) // 안내 단계
    val group = groups[gi]
    val within = if (group.size > 1) (group.indexOf(stepIndex) + 1) to group.size else null
    return RoutineProgressLabel(groups.size, gi + 1, within)
}

/**
 * 진행바 채움 비율(0~1) — 루틴 전체 계획 시간 대비 지금까지 흐른 시간. 안내 단계에서도 자연스럽게 흐르고
 * 마지막에 1.0 에 수렴한다. 현재 단계 안의 진행(elapsedMsInStep)까지 반영해 부드럽게 채운다.
 */
internal fun routineElapsedFraction(steps: List<Step>, stepIndex: Int, elapsedMsInStep: Long): Float {
    val total = steps.sumOf { it.sec }.coerceAtLeast(1)
    val before = steps.take(stepIndex.coerceIn(0, steps.size)).sumOf { it.sec }
    val cur = steps.getOrNull(stepIndex)?.let {
        (elapsedMsInStep / 1000f).coerceIn(0f, it.sec.toFloat())
    } ?: 0f
    return ((before + cur) / total).coerceIn(0f, 1f)
}

/** 진행 캡션 문구. 안내 단계면 null(캡션 미표시). 예: "9개 동작 중 3번째", "9개 동작 중 3번째 (2/6)". */
internal fun routineProgressCaption(label: RoutineProgressLabel): String? {
    val idx = label.groupIndex ?: return null
    val base = "${label.groupTotal}개 동작 중 ${idx}번째"
    return label.withinGroup?.let { (m, n) -> "$base ($m/$n)" } ?: base
}
