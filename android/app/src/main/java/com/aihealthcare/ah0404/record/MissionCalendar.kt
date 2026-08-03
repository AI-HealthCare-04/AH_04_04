package com.aihealthcare.ah0404.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.Dimens
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

// 미션 달력(#기록탭 §5.2) — 월 뷰 + 스탬프(⭐ 성공 / 🏆 대성공). 색 단독 금지라 이모지+텍스트 병기.

private val KST_CAL: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

/** "YYYY-MM" 문자열. */
internal fun monthKey(year: Int, month1: Int): String = String.format(Locale.US, "%04d-%02d", year, month1)

/** 날짜 원 지름·오늘 테두리 두께(§7 M2). */
private val CalendarCircleSize = 28.dp
private val TodayBorder = 1.5.dp

private fun dayKeyOf(year: Int, month1: Int, day: Int): String =
    String.format(Locale.US, "%04d-%02d-%02d", year, month1, day)

/**
 * daily_result → 스탬프 이모지("" = 없음).
 *
 *  성공(⭐)은 #387 리디자인에서 **연녹색 원 채움**으로 바뀌어 이모지를 쓰지 않는다 —
 *  칸마다 이모지가 붙으면 숫자가 묻히고, 성공/대성공이 별·트로피로 둘 다 이모지라 구분도 약했다.
 *  대성공만 원 위에 트로피를 얹어 **색 + 모양**으로 구분한다(§10 색 단독 금지).
 */
internal fun stampEmoji(dailyResult: String?): String = when (dailyResult) {
    "great_success" -> "🏆"
    else -> ""
}

private fun stampLabel(dailyResult: String?): String = when (dailyResult) {
    "great_success" -> "대성공"
    "success" -> "성공"
    else -> ""
}

@Composable
internal fun MissionCalendar(
    year: Int,
    month1: Int, // 1~12
    resultByDate: Map<String, String>, // dateKey -> daily_result
    // '기록함(목표 미달성)' 날짜(#343 문제 2) — 스탬프 없는 날에만 옅은 점(·)으로 표시해
    //   완료 스탬프(⭐/🏆)의 '목표 달성' 의미를 흐리지 않는다.
    recordedOnlyDates: Set<String> = emptySet(),
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDaySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = GregorianCalendar(KST_CAL).apply { clear(); set(year, month1 - 1, 1) }
    val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leadingBlanks = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY // 0=일요일 시작
    val weekdayLabels = listOf("일", "월", "화", "수", "목", "금", "토")
    val today = GregorianCalendar(KST_CAL)
    val todayKey = dayKeyOf(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
            }
            Text(
                "${year}년 ${month1}월",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { d ->
                Text(
                    d, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(Dimens.Space4))
        val cells = leadingBlanks + daysInMonth
        val rows = (cells + 6) / 7
        var day = 1
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    if (index < leadingBlanks || day > daysInMonth) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val dk = dayKeyOf(year, month1, day)
                        val result = resultByDate[dk]
                        val emoji = stampEmoji(result)
                        val recordedOnly = emoji.isEmpty() && dk in recordedOnlyDates
                        val dayNum = day
                        val cellDesc = when {
                            emoji.isNotEmpty() -> "${month1}월 ${dayNum}일 ${stampLabel(result)}"
                            recordedOnly -> "${month1}월 ${dayNum}일 기록 있음"
                            else -> "${month1}월 ${dayNum}일"
                        }
                        DayCell(
                            day = dayNum,
                            marked = result == "success" || result == "great_success",
                            great = result == "great_success",
                            isToday = dk == todayKey,
                            recordedOnly = recordedOnly,
                            description = cellDesc,
                            onClick = { onDaySelected(dk) },
                            modifier = Modifier.weight(1f),
                        )
                        day++
                    }
                }
            }
        }
        // 범례는 표시할 마크가 있을 때만(§7 M2 — 빈 달에서 범례만 남으면 무엇을 설명하는지 알 수 없다).
        if (resultByDate.values.any { it == "success" || it == "great_success" }) {
            Spacer(Modifier.height(Dimens.Space8))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
                LegendItem("성공") { MarkerCircle(filled = true) }
                LegendItem("대성공") { Text("🏆", style = MaterialTheme.typography.bodySmall) }
                LegendItem("오늘") { MarkerCircle(filled = false) }
            }
        }
    }
}

/**
 * 날짜 한 칸(§7 M2). 셀 높이 40dp / 원 지름 28dp.
 *  - 성공: 연녹색 원 채움 + 숫자 Bold
 *  - 대성공: 성공과 동일 + 원 우측 상단 트로피(색 단독 금지 — §10)
 *  - 오늘: 위 상태에 더해 진녹 테두리. 성공이면 채움과 테두리를 함께 그린다.
 *  터치 영역은 셀 전체(가로 1/7 × 세로 40dp)이며, 시니어 기준(§10)에 맞춰 최소 48dp 를 별도로 확보한다.
 */
@Composable
private fun DayCell(
    day: Int,
    marked: Boolean,
    great: Boolean,
    isToday: Boolean,
    recordedOnly: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(CalendarCircleSize)
                    .then(
                        if (marked) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (isToday) {
                            Modifier.border(TodayBorder, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$day",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (marked) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (great) {
                Text(
                    "🏆",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 9.dp, y = (-7).dp),
                )
            }
            // 기록만 있는 날(#343): 스탬프와 구분되는 옅은 점. 원 아래에 붙인다.
            if (recordedOnly && !marked) {
                Text(
                    "·",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 범례용 마커 — 채움(성공) / 테두리만(오늘). 달력 원과 같은 규칙을 작게 재현한다. */
@Composable
private fun MarkerCircle(filled: Boolean) {
    Box(
        Modifier
            .size(12.dp)
            .then(
                if (filled) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                } else {
                    Modifier.border(TodayBorder, MaterialTheme.colorScheme.primary, CircleShape)
                },
            ),
    )
}

@Composable
private fun LegendItem(label: String, marker: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        marker()
        Spacer(Modifier.width(Dimens.Space4))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
