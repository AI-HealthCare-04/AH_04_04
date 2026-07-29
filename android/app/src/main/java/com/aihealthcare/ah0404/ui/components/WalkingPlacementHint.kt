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
 * 걷기 측정 중 '걸음이 계속 0'일 때의 폰 배치 안내(#232, 검출기 무수술 UX 완화).
 *
 * 배경: #176(손 파지·느린·발끌기 보행 과소계수) K2 실험 결과, 현재 가속도계 신호만으로는
 *   느린/약한 보행 회복과 앉기 전환 오탐 억제를 동시에 만족할 수 없어 K2는 Post-v1 동결됐다.
 *   그 결과 손 파지/느린 보행에서 걸음이 0으로 표시되는 잔여 신뢰 문제가 남는다. 미션 성공은
 *   이미 시간 기반(누적분)이라 미션 자체는 정상 성공하지만, "경과 시간은 흐르는데 걸음이 계속 0"인
 *   화면은 오작동처럼 보일 수 있다. 검출기/상태머신은 건드리지 않고(알고리즘 리스크 0) 순수 안내로 완화한다.
 *
 * 노출 조건은 순수 함수(shouldShowPlacementHint)로 분리해 단위 검증한다 — 여기서는 표시만 담당한다.
 * 스타일: 디자인 토큰만 사용(WalkSitGuidanceNote 패턴). 앉기 안내(primaryContainer)와 구분하려
 *   tertiaryContainer 를 쓴다.
 */
@Composable
fun WalkingPlacementHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            Text(
                "🚶 걸음이 잘 잡히지 않고 있어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "휴대폰을 주머니에 넣거나 손에 쥔 채 팔을 자연스럽게 흔들며 걸으면 더 정확해요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * '걸음 수는 참고 정보'임을 알리는 보조 문구(#232).
 *
 * 미션 성공 판정은 서버가 당일 누적 '시간'으로 하므로(#91), 걸음 수는 정확 측정값이 아니라
 *   참고 지표다. 측정 화면·기록 화면 양쪽에 같은 카피로 노출해, 걸음 수가 낮게/높게 나와도
 *   미션 성공과 무관함을 알린다(과다카운트·과소계수 잔여 문제의 사용자 혼란 완화).
 * 카드가 아닌 옅은 캡션으로 둬 화면 잡음을 줄인다.
 */
@Composable
fun WalkingStepsReferenceNote(modifier: Modifier = Modifier) {
    Text(
        text = "걸음 수는 참고용이에요 — 미션 성공은 걸은 시간으로 정해져요.",
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
