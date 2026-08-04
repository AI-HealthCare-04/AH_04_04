package com.aihealthcare.ah0404.record

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.fitness.StsAssessmentScreen
import com.aihealthcare.ah0404.network.StsAssessmentItem
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 5STS 재측정·추이 카드(#353) — 기록 탭 '나의 기록' 점수 추이 아래 보조 지표.
 *
 *  - 재측정 진입점: 온보딩의 [StsAssessmentScreen] 을 전체화면 다이얼로그로 그대로 재사용(요청 1).
 *    온보딩에서 건너뛴 사용자도 여기서 처음 측정할 수 있다.
 *  - 추이(요청 3): 최신값 + 직전 대비 변화 문구(개선=감소, 방향 반전) + 최근 측정 목록.
 *    12초 기준선은 표시하지 않는다 — 판정으로 읽힐 수 있어 비의료 포지셔닝(#57) 유지.
 *  - 점수(score) 유무와 무관하게 표시한다: 5STS 는 직접 수행 지표라 예측 점수가 없는 사용자
 *    (스킵·65세 미만 등)에게도 유효하다.
 */
@androidx.annotation.OptIn(UnstableApi::class) // StsAssessmentScreen(미디어 가이드 영상) 재사용 — 호출부 전파 없이 opt-in
@Composable
internal fun StsTrendCard(vm: StsTrendViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    var measuring by rememberSaveable { mutableStateOf(false) }

    StsTrendCardBody(
        history = vm.history,
        loaded = vm.loaded,
        loadError = vm.loadError,
        saving = vm.saving,
        saveError = vm.saveError,
        onReload = vm::load,
        onRetrySave = vm::retrySubmit,
        onMeasure = { measuring = true },
    )

    if (measuring) {
        // 전체화면 측정(#353 요청 1): 온보딩 화면을 그대로 재사용. usePlatformDefaultWidth=false 로
        //   다이얼로그를 전체화면으로 펼친다(별도 내비게이션 없이 카드 파일 안에서 완결 — #339 충돌 최소화).
        Dialog(
            onDismissRequest = { measuring = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                StsAssessmentScreen(
                    onMeasured = { seconds ->
                        vm.submit(seconds)
                        measuring = false
                    },
                    onCancel = { measuring = false },
                )
            }
        }
    }
}

/**
 * 5STS 카드의 표시 부분(#387) — **상태를 받기만 한다.** ViewModel 을 직접 읽지 않으므로
 * Preview 와 UI 테스트가 임의의 상태로 그릴 수 있다(측정 다이얼로그는 호출부가 갖는다).
 */
@Composable
internal fun StsTrendCardBody(
    history: List<StsAssessmentItem>,
    loaded: Boolean,
    loadError: Boolean,
    saving: Boolean,
    saveError: Boolean,
    onReload: () -> Unit,
    onRetrySave: () -> Unit,
    onMeasure: () -> Unit,
) {
    AigoCard(title = "5회 의자 일어서기(5STS)", contentSpacing = Dimens.Space12) {
        val latest = history.firstOrNull()
        // 조회 실패(리뷰 #355 P2)는 '측정 전'과 구분해 표시 — 기존 목록이 있으면 목록은 그대로 두고 안내만 얹는다.
        if (loadError) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                Text(
                    "측정 이력을 불러오지 못했어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                AigoSecondaryButton(text = "다시 불러오기", onClick = onReload)
            }
        }

        // 좌: 기록·변화·버튼 / 우: 일러스트(§8 H4). 일러스트는 장식이라 좁은 화면에서 텍스트를 밀지 않게
        //   텍스트 쪽에 weight 를 준다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
            ) {
                if (latest == null) {
                    if (loaded && !loadError) {
                        Text(
                            "아직 측정 기록이 없어요",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "지금 한 번 재보실래요?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 숫자를 문장에서 떼어 크게 — 시니어가 초 단위 값을 먼저 읽게 한다(§8 H4).
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("최근 기록 ", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stsSecondsLabel(latest.chairStand5TimeSec).removeSuffix("초"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("초", style = MaterialTheme.typography.bodyLarge)
                    }
                    // 직전 대비 변화(측정 2회 이상일 때만) — 개선=감소라 문구 방향이 점수와 반대(#353 요청 3).
                    stsChangeLine(history.getOrNull(1)?.chairStand5TimeSec, latest.chairStand5TimeSec)?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.Space4))
                // 측정 전에는 주 행동이라 채움 버튼, 기록이 있으면 보조 행동이라 외곽선 버튼(§8 H4).
                if (latest == null) {
                    AigoPrimaryButton(
                        text = "체력 검사 해보기",
                        onClick = onMeasure,
                        enabled = !saving && !saveError,
                    )
                } else {
                    AigoSecondaryButton(
                        text = "체력 검사 다시 하기",
                        onClick = onMeasure,
                        // 저장 미해결(saveError) 동안 재측정 금지(리뷰 #355 3차): 새 측정값이 완료 여부 불명확한
                        //   세션에 붙어 영구 409 가 되는 경로 차단 — 아래 '저장 다시 시도'로 먼저 해소해야 한다.
                        enabled = !saving && !saveError,
                    )
                }
            }
            Image(
                painter = painterResource(R.drawable.img_record_sts_seated),
                contentDescription = null, // 장식용(§10)
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit,
            )
        }

        // 최근 측정 목록(#353 요청 3) — 두 번 이상 잰 사람에게만.
        if (history.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                stsRecentLines(history).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 권장 주기 안내(#353 요청 1) — 3개월 근거는 STS_REMEASURE_INTERVAL_NOTICE·docs/sts_remeasure_interval.md.
        Text(
            STS_REMEASURE_INTERVAL_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saveError) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                Text(
                    "측정 결과를 저장하지 못했어요. 아래 '저장 다시 시도'로 먼저 저장을 마쳐 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // 측정값은 보관돼 있어 다시 재지 않고 저장만 재시도한다(시니어 UX — 재측정 강요 금지).
                AigoSecondaryButton(text = "저장 다시 시도", onClick = onRetrySave, enabled = !saving)
            }
        }
    }
}
