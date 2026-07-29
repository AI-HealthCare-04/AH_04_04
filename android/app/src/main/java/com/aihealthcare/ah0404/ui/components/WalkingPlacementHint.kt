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
                // 아직 걷지 않은 사용자(측정 시작 후 대기)에게도 뜰 수 있어 '실패' 단정 대신 중립 문두를 쓴다(리뷰 반영).
                "🚶 아직 걸음이 잡히지 않아요",
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
 * '걸음 수는 참고 정보'임을 알리는 보조 문구(#232). 표시만 담당하는 순수 컴포넌트다.
 *
 * 시간(minutes) 목표 미션에서만 미션 성공이 당일 누적 '시간'으로 정해지므로(#91) 걸음 수가
 *   참고 지표다. 걸음(steps) 목표 미션은 걸음이 곧 성공 지표라 이 문구가 목표와 모순되므로,
 *   노출 여부·카피는 호출부의 순수 함수(walkingStepsReferenceNoteText)가 결정하고 여기서는
 *   전달받은 [text] 만 그린다(리뷰 반영). 카드가 아닌 옅은 캡션으로 둬 화면 잡음을 줄인다.
 */
@Composable
fun WalkingStepsReferenceNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
