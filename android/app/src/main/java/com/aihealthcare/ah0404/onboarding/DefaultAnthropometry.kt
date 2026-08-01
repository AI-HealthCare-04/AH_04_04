package com.aihealthcare.ah0404.onboarding

private const val FIRST_ESTIMATE_AGE = 50
private const val LAST_SINGLE_AGE = 79
private const val DEFAULT_ESTIMATE_AGE = 65

/**
 * 국민건강영양조사(KNHANES) 2022~2024 원시자료 기반 키·몸무게 중앙값(cm, kg).
 *
 * 전달 산출물 `default_anthropometry_5079_pm3.json`을 정본으로 사용한다. 신장·체중이 모두 유효한
 * 응답자를 성별과 단일 나이로 나눈 뒤 ±3세(7년) 창에서 미가중 중앙값을 구했으며, 원자료에서 나이가
 * top-coding되는 80세 이상은 별도 풀의 중앙값을 사용한다. 50세 미만은 UI에서 추정 입력을 허용하지
 * 않으며, 이 함수가 직접 호출되면 가장 가까운 50세 값으로 제한한다.
 */
private val maleAnthropometry = listOf(
    172.5 to 74.5, // 50
    172.2 to 73.4,
    171.7 to 73.2,
    171.4 to 72.9,
    171.1 to 72.4,
    170.6 to 72.0,
    170.4 to 71.8,
    170.3 to 71.1,
    170.0 to 71.0,
    169.8 to 70.6,
    169.6 to 70.4, // 60
    169.3 to 69.5,
    169.3 to 69.3,
    168.8 to 69.1,
    168.7 to 69.2,
    168.5 to 68.9,
    168.1 to 68.4,
    167.9 to 68.0,
    167.6 to 67.8,
    167.3 to 67.4,
    167.0 to 66.8, // 70
    166.7 to 66.1,
    166.6 to 65.9,
    166.3 to 65.7,
    166.2 to 65.8,
    166.1 to 65.7,
    165.9 to 65.3,
    165.9 to 65.3,
    165.9 to 65.3,
    165.9 to 65.3, // 79
)

private val femaleAnthropometry = listOf(
    159.5 to 58.0, // 50
    159.3 to 57.9,
    159.0 to 57.9,
    159.0 to 58.0,
    158.8 to 57.7,
    158.4 to 58.0,
    158.0 to 58.0,
    157.7 to 57.9,
    157.5 to 58.0,
    157.3 to 58.0,
    156.9 to 57.7, // 60
    156.7 to 58.0,
    156.5 to 57.7,
    156.3 to 57.7,
    156.0 to 57.9,
    155.6 to 57.8,
    155.2 to 57.8,
    155.1 to 57.7,
    154.9 to 57.7,
    154.7 to 57.4,
    154.3 to 57.1, // 70
    153.8 to 56.9,
    153.6 to 56.5,
    153.1 to 56.5,
    152.7 to 56.4,
    152.6 to 56.2,
    152.4 to 56.2,
    152.4 to 56.2,
    152.4 to 56.2,
    152.4 to 56.2, // 79
)

private val male80Plus = 164.0 to 61.2
private val female80Plus = 149.3 to 53.0

internal fun estimateBody(sex: String?, age: Int?): Pair<Double, Double> {
    val isFemale = sex == "female"
    val resolvedAge = age ?: DEFAULT_ESTIMATE_AGE
    if (resolvedAge > LAST_SINGLE_AGE) return if (isFemale) female80Plus else male80Plus

    val index = resolvedAge.coerceAtLeast(FIRST_ESTIMATE_AGE) - FIRST_ESTIMATE_AGE
    return if (isFemale) femaleAnthropometry[index] else maleAnthropometry[index]
}
