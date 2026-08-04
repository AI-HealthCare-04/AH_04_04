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
import com.aihealthcare.ah0404.ui.theme.Dimens

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
 * 카피(리뷰 #141): 주 문구는 완화 목적을 명확히("2초 정도 멈춰 선 뒤 천천히"), 보조 문구는
 *   계측 오차를 강조하는 대신 행동 이유를 긍정형으로("정확하게 기록") 안내한다.
 * 스타일: 디자인 토큰(Dimens/Typography)만 사용한다 — titleMedium/bodyMedium은 fontSize에
 *   lineHeight가 짝지어 정의돼 있어 320dp에서 줄바꿈돼도 간격이 유지되고, 시니어 대응으로
 *   스케일을 조정할 때 이 공용 컴포넌트에도 함께 반영된다(하드코딩 금지, MedicalDisclaimer 패턴).
 */
@Composable
fun WalkSitGuidanceNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            Text(
                "💡 걷기를 마치면 2초 정도 멈춰 선 뒤 천천히 앉아 주세요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "걸음 수를 정확하게 기록하기 위해서예요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
