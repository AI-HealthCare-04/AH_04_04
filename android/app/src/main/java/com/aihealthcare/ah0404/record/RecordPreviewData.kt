package com.aihealthcare.ah0404.record

/**
 * Preview 전용 더미 데이터(#387, 핸드오프 §11).
 *
 *  ⚠️ **프로덕션 코드에서 참조하지 않는다.** 화면·ViewModel 은 서버 값만 쓰고, 이 파일은
 *   `RecordPreviews.kt` 에서만 읽는다. 시안(`reference/01`~`04`)과 눈으로 대조하려고
 *   시안에 적힌 예시 수치를 그대로 옮겨 놨다.
 */
internal object RecordPreviewData {

    // ── 미션 기록 · 데이터 있음(시안 01) ─────────────────────────────────────
    val monthSummary = MonthSummary(joinedDays = 12, successCount = 7, greatSuccessCount = 5)

    /** 시안 01 의 걷기 20·25·18·30·22·28·25분. */
    val walk7d = listOf(
        WalkPoint("월", 20),
        WalkPoint("화", 25),
        WalkPoint("수", 18),
        WalkPoint("목", 30),
        WalkPoint("금", 22),
        WalkPoint("토", 28),
        WalkPoint("일", 25),
    )

    /** 시안 01 의 걷기 4 · 운동 3 · 식사 5 · 게임 2. */
    val missionProgress = listOf(
        MissionProgress(MissionKind.WALK, done = 4),
        MissionProgress(MissionKind.EXERCISE, done = 3),
        MissionProgress(MissionKind.MEAL, done = 5),
        MissionProgress(MissionKind.GAME, done = 2),
    )

    // ── 미션 기록 · 데이터 없음(시안 02) ─────────────────────────────────────
    val emptyMonthSummary = MonthSummary(joinedDays = 0, successCount = 0, greatSuccessCount = 0)

    /** 시안 02 는 식사만 색이 살아 있다 — done == 0 인 타일이 회색이 되는지 확인용. */
    val emptyMissionProgress = listOf(
        MissionProgress(MissionKind.WALK, done = 0),
        MissionProgress(MissionKind.EXERCISE, done = 0),
        MissionProgress(MissionKind.MEAL, done = 6),
        MissionProgress(MissionKind.GAME, done = 0),
    )

    // ── 근육 건강 · 데이터 있음(시안 03) ─────────────────────────────────────
    /** 시안 03 의 추이 67 → 70 → 69 → 76 → 73. */
    val trend = listOf(
        ScorePoint("07.01", 67),
        ScorePoint("07.15", 70),
        ScorePoint("07.29", 69),
        ScorePoint("08.02", 76),
        ScorePoint("08.02", 73),
    )

    val muscleScore = MuscleScoreUi(
        age = 67,
        score = 73,
        band = "maintain", // 시안의 "유지" 배지
        predictionId = null, // 피드백 카드는 Preview 범위 밖(별도 노출 정책)
        trend = trend,
        walkSim = emptyList(),
        muscSim = emptyList(),
        stsSeconds = null,
        bmi = null,
        cohort = null, // 또래 카드는 실제 분포 응답이 있어야 그려진다
        waistCm = 82,
        measuredAtIso = "2026-08-02",
    )

    // ── 근육 건강 · 데이터 없음(시안 04) ─────────────────────────────────────
    val emptyMuscleScore = MuscleScoreUi(
        age = 67,
        score = null,
        band = null,
        trend = emptyList(),
        walkSim = emptyList(),
        muscSim = emptyList(),
        stsSeconds = null,
        bmi = null,
        cohort = null,
        waistCm = null,
        measuredAtIso = null,
    )
}
