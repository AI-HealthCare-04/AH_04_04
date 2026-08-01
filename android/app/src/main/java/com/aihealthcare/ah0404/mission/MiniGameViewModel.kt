package com.aihealthcare.ah0404.mission

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch

/**
 * 미니게임 완료 기록(#348 리뷰 P2) — 영상 완주 시 게임 미션 완료를 서버에 1회 기록한다.
 *
 *  게임은 즉시완료 유형(#93)이라 POST /mission-logs(status=completed) 한 번이면 되고, 하루 1회
 *  상한(#272)은 서버가 counted_for_daily 로 판정하므로 재완주를 다시 보내도 이중 적립이 없다.
 *  이 VM 이 게임 완료의 **유일한 기록 지점**이다(#91 단일 기록 지점 원칙 — 화면·홈 등 다른 경로 금지).
 *
 *  실패는 조용히 삼킨다: 완료 기록은 배지(#346)·포인트용 부가 흐름이라, 네트워크 오류가 자동 복귀(#344)
 *  자체를 막으면 안 된다. 성공 시에만 [onRecorded] 로 목록 재조회를 트리거해 today_done 을 갱신한다.
 */
class MiniGameViewModel(
    // 테스트 주입용 기본 인자 — 프로덕션은 공용 retrofit.
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
) : ViewModel() {

    private var inFlight = false

    fun recordCompletion(mission: Mission, onRecorded: () -> Unit) {
        if (inFlight) return // 완주 콜백 중복 발화 방어(리스너 재부착 등) — 한 번만 기록
        inFlight = true
        viewModelScope.launch {
            try {
                api.createMissionLog(
                    MissionLogCreateRequest(
                        missionTemplateId = mission.missionTemplateId,
                        missionType = "game",
                        status = "completed",
                    )
                )
                onRecorded()
            } catch (e: Exception) {
                Log.w(TAG, "게임 완료 기록 실패(복귀는 정상 진행): ${e.message}")
            } finally {
                inFlight = false
            }
        }
    }

    private companion object { const val TAG = "MiniGame" }
}
