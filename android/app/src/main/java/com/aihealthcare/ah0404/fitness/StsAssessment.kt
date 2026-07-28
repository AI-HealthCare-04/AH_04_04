package com.aihealthcare.ah0404.fitness

/**
 * 기초체력 평가(5회 의자 일어서기, Five Times Sit-to-Stand) 순수 로직·문구.
 *
 * 프레임워크(TTS·ExoPlayer·Compose) 없이 검증 가능한 부분만 모은다:
 *  - 화면 단계, 목표 횟수, 회당 발화 단어(반응형 카운트), 결과 문구.
 *
 * ⚠️ 비의료 포지셔닝(하드 제약): "진단/질환/저하 판정" 같은 확정 표현 금지. 결과는 시간만 읽거나
 *    "전문가 상담 권유"까지만. 참고 기준선(AWGS 12초)은 판정이 아니라 안내 트리거로만 쓴다.
 */
enum class StsStage {
    /** 방법 안내(앉은/선 자세 이미지 + 반복 영상 + 설명). */
    GUIDE,

    /** 안전 확인(어지럼·보호자 동석). */
    SAFETY,

    /** 준비(시작 버튼 대기). */
    READY,

    /** 측정(스톱워치 + 회당 버튼 + 반응형 카운트). */
    MEASURING,

    /** 결과(소요 시간 표시·기록). */
    RESULT,
}

/** 목표 반복 횟수 — 5회째 완전히 일어서면 측정 종료. */
const val STS_TARGET_REPS = 5

/**
 * 회당 반응형 발화 단어. **사용자가 이미 한 동작을 확인해주는 역할**이지 다음 동작을 지시하지 않는다
 * (메트로놈 금지 — 일정 리듬으로 세면 사용자가 속도를 맞춰버려 '가능한 빨리' 측정이 무효가 된다).
 * 범위를 벗어나면 빈 문자열(발화 안 함).
 */
fun stsCountWord(rep: Int): String = when (rep) {
    1 -> "하나"
    2 -> "둘"
    3 -> "셋"
    4 -> "넷"
    5 -> "다섯"
    else -> ""
}

/** 시작 카운트다운 발화. "시작하세요" 발화 시점에 스톱워치를 start 한다(발화 완료 대기 X). */
val STS_COUNTDOWN_WORDS = listOf("셋", "둘", "하나", "시작하세요!")

/** 결과 발화 — 숫자만 읽어준다(시력 저하 대응). 판정 표현 금지. */
fun stsResultSpeech(seconds: Double): String = "${formatStsSeconds(seconds)}초 걸리셨어요."

/** 소요 시간 표기(0.1초 단위). */
fun formatStsSeconds(seconds: Double): String {
    val tenths = Math.round(seconds * 10.0)
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * 측정 종료 판정: 방금 늘린 반복 횟수가 목표(5)에 도달했는가.
 * (5회째 [일어섰어요] 탭에서 자동 종료 — 별도 완료 버튼 불필요.)
 */
fun stsIsComplete(reps: Int): Boolean = reps >= STS_TARGET_REPS

/**
 * 시작 카운트다운에서 스톱워치를 start 할 인덱스인가 — 마지막 단어("시작하세요!") 발화 시점.
 * 그 뒤에 delay 를 두면 첫 ~0.8초가 측정에서 빠진다(리뷰 #221-1). 이 계약을 테스트로 고정한다.
 */
fun stsClockStartsAtCountdownIndex(index: Int): Boolean = index == STS_COUNTDOWN_WORDS.lastIndex
