package com.aihealthcare.ah0404.mission

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * '준비 중' 안내 화면 (#93 A-3) — 아직 수행 화면이 없는 미션 유형(운동·식사·게임 등)이
 * 눌렸을 때 연결되는 자리표시자. 라우팅만 담당하며 서버 기록은 만들지 않는다.
 *
 * 실제 수행·기록 생성은 유형별 후속 이슈에서 이 자리에 화면을 붙이면 된다.
 * (걷기는 여기로 오지 않는다 — WalkingMeasureScreen 으로 직접 라우팅됨.)
 */
@Composable
fun ComingSoonScreen(
    mission: Mission,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16, Alignment.CenterVertically),
    ) {
        Text(
            text = mission.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        AigoCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "🛠️ ${comingSoonLabel(mission.missionType)}은 준비 중이에요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                text = "다음 업데이트에서 이 미션을 바로 할 수 있게 준비하고 있어요.\n조금만 기다려 주세요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AigoPrimaryButton(text = "돌아가기", onClick = onBack)
    }
}

/** 미션 유형별 사람이 읽기 좋은 이름(안내 문구용). 알 수 없는 유형은 '이 미션'. */
private fun comingSoonLabel(missionType: String): String = when (missionType) {
    "exercise" -> "운동 미션"
    "meal" -> "식사 미션"
    "game" -> "게임 미션"
    else -> "이 미션"
}
