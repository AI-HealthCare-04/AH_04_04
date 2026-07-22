package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 걷기 미션 안내 카드 — '보행 직후 곧바로 앉기' 과다카운트에 대한 단기 안전 완화(#132).
 *
 * 배경: '정상 보행 직후 곧바로 앉기'는 앉는 동작의 피크 간격(≈882ms)이 정상 보행 대역 안이라
 *   간격 게이트만으로 분리할 수 없어 과다카운트가 남는다(#89 §5-5의 알려진 한계, 근본 개선은 #131).
 *   별도 '측정 종료' 버튼이 없으므로, 앉기 전 잠시 멈추도록 안내해 완화한다.
 *
 * 걷기 측정이 실제로 도는 화면에 배치한다(현재 SensorScreen). 향후 걷기 미션 측정 화면이
 *   구현되면 그 화면에서도 재사용한다.
 *
 * ⚠️ 카피는 초안 — 팀 확인 필요(#132 "카피 문구 팀 확인").
 */
@Composable
fun WalkSitGuidanceNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "💡 걷기를 마치면 잠시 멈춘 뒤 앉아 주세요",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "걷다가 바로 앉으면 걸음이 실제보다 조금 많게 셀 수 있어요.",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
