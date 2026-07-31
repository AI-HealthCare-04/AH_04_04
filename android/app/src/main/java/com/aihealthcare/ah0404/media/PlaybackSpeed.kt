package com.aihealthcare.ah0404.media

import android.content.Context
import androidx.media3.common.Player
import com.aihealthcare.ah0404.settings.AppSettings

/**
 * 컨트롤러 톱니로 바뀐 재생 속도(raw)를 확정 옵션으로 정규화해 전역 영속하고, 옵션 밖 값이었다면
 * 플레이어에도 정규화 값을 되돌려 적용한다(#288 — ExercisePlayer/StreamingVideoPlayer 의
 * onPlaybackParametersChanged 중복 블록 통합). AppSettings 는 미디어 의존이 없도록 유지하고,
 * Player 결합은 이 확장에만 둔다.
 */
internal fun Player.persistNormalizedSpeed(context: Context, rawSpeed: Float) {
    val normalized = AppSettings.normalizeSpeed(rawSpeed)
    AppSettings.setPlaybackSpeed(context, normalized)
    if (rawSpeed != normalized) setPlaybackSpeed(normalized)
}
