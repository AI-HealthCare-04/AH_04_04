package com.aihealthcare.ah0404.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

private fun dayKeyOf(year: Int, month1: Int, day: Int): String =
    String.format(Locale.US, "%04d-%02d-%02d", year, month1, day)

/** daily_result → 스탬프 이모지("" = 없음). */
internal fun stampEmoji(dailyResult: String?): String = when (dailyResult) {
    "great_success" -> "🏆"
    "success" -> "⭐"
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
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clickable { onDaySelected(dk) }
                                .semantics { contentDescription = cellDesc },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$dayNum", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when {
                                        emoji.isNotEmpty() -> emoji
                                        recordedOnly -> "·" // 기록만 있는 날(#343): 스탬프와 구분되는 옅은 점
                                        else -> " "
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (recordedOnly) MaterialTheme.colorScheme.onSurfaceVariant
                                    else Color.Unspecified,
                                )
                            }
                        }
                        day++
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.Space8))
        // 범례(색 단독 금지 — 이모지+텍스트)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
            LegendItem("⭐", "성공")
            LegendItem("🏆", "대성공")
        }
    }
}

@Composable
private fun LegendItem(emoji: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(Dimens.Space4))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
