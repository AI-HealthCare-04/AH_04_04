package com.aihealthcare.ah0404.mission

/**
 * 걷기 피드백 음성 문구 — 로직(실행기)과 분리해 한곳에 모은다(리뷰 #148).
 *
 *  팀·비개발자가 코드를 파고들지 않고 문구만 검토·수정할 수 있게, 발화 텍스트를 `speak()` 안에서
 *  꺼내 확장 프로퍼티로 둔다. strings.xml 대신 Kotlin 을 쓰는 건 이 앱이 화면 문구를 전부 코드에
 *  두기 때문(문구 위치가 두 군데로 갈리지 않게). 운동 TTS 가 붙으면 같은 파일에 추가하면 된다.
 *
 *  ⚠️ TTS 문구는 화면 문구와 달리 "다시 볼 수 없고" 길이가 곧 재생 시간이다 — 짧고 한 번에
 *     알아듣게 한다. STARTED 는 보행이 이미 확정된(≈10걸음 뒤) 순간에 울리므로 "화면 안 봐도
 *     걸으셔도 돼요" 같은 사전 안내를 넣지 않는다(시점이 어긋난다 — 그 안내는 시작 버튼 근처의
 *     화면 문구 몫이다).
 */
internal val WalkingFeedbackCue.speech: String
    get() = when (this) {
        WalkingFeedbackCue.STARTED -> "측정을 시작했어요."
        WalkingFeedbackCue.GOAL_REACHED -> "목표를 달성했어요. 천천히 마무리해 주세요."
    }
