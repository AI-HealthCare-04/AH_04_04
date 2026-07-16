package com.aihealthcare.ah0404.routine

/**
 * 운동 루틴 데이터 모델 (data-driven 플레이어의 입력).
 * 화면은 루틴마다 만들지 않고, 이 데이터 + 플레이어 1개로 재생한다.
 * 루틴 추가 = assets/routines 폴더에 json 파일 추가만으로 되게 한다.
 */

enum class StepType { INTRO, VIDEO, IMAGE, NOTICE, OUTRO }

/** timer=원형 카운트다운 / count=횟수 표시 / none=표시 없음(intro·notice·outro) */
enum class StepMode { TIMER, COUNT, NONE }

data class Step(
    val type: StepType,
    val sec: Int,             // 이 단계 재생 시간(초)
    val name: String,         // 동작명(대형 표시)
    val guide: String = "",   // 방법 안내 1줄
    val asset: String? = null,      // 리소스 이름(확장자 없음). video→res/raw, image→assets/exercise
    val mode: StepMode = StepMode.NONE,
    val count: Int? = null,   // mode=count일 때 목표 횟수
    val mirror: Boolean = false,    // true면 좌우 반전(측면 뷰에만 사용)
    val safety: String? = null,     // 있으면 안전 경고 상시 노출
)

data class Routine(
    val id: String,
    val title: String,
    val subtitle: String,
    val bgm: String,          // res/raw BGM 리소스 이름
    val totalSec: Int,
    val steps: List<Step>,
)
