package com.aihealthcare.ah0404.sensor

import java.util.Locale

/**
 * ============================================================================
 *  WaveformCapture : '보행 직후 앉기' 분리를 위한 원시 3축 파형 수집 도구의 **순수 코어** (#131)
 * ============================================================================
 *
 *  배경(#89 §5-5): '정상 보행 직후 곧바로 앉기'는 앉기 피크 간격(≈882ms=68보/분)이 정상 보행
 *  대역(500~1000ms) 한가운데라, 현재 감지기가 쓰는 **간격(interval)이라는 단일 신호로는 분리 불가**다
 *  → v1 미보장(과다카운트). #131은 간격 외의 신호(축 방향·진폭·분산 등)로 이를 분리하려 하는데,
 *  그 신호를 찾으려면 **정상 보행 vs 보행 직후 앉기의 라벨링된 원시 3축 파형**이 먼저 있어야 한다.
 *
 *  이 파일은 그 데이터를 모으는 도구의 순수 부분이다:
 *   - WalkingStepDetectorLogic 이 버리는 x/y/z 원시값과, 그 순간의 파생값(magnitude·필터값·상태·
 *     걸음 카운트 여부)을 한 행으로 묶어 라벨과 함께 CSV 로 직렬화한다.
 *   - Android 의존이 없어 JVM 단위 테스트로 포맷·상태 전이를 고정한다(수집 UI/파일 IO 는 debug 소스셋).
 *
 *  ⚠️ 이 단계는 **데이터 수집 도구**까지다. 실제 판별 특징·임계값·상태 머신 변경은 수집된 실측
 *     파형으로 탐색한 뒤 후속 단계에서 설계한다(현 PR 범위 밖). 감지기 로직은 건드리지 않는다.
 * ============================================================================
 */

/** 캡처 시나리오 라벨 — 두 동작을 대조해 판별 특징을 찾기 위해 파일·행에 함께 기록한다. */
enum class WaveformLabel(val id: String, val display: String) {
    /** 정상 보행만(대조군). */
    NORMAL_WALK("normal_walk", "정상 보행"),

    /** 정상 보행 직후 곧바로 앉기(오탐 대상, §5-5). */
    WALK_THEN_SIT("walk_then_sit", "보행 직후 앉기"),
}

/**
 * 가속도 샘플 1개 + 그 순간 감지기 상태의 스냅샷.
 * 원시 3축(x,y,z)은 특징 탐색의 핵심이고, 파생값은 '감지기가 어디서 오탐하는지'를 같은 행에서 보기 위함이다.
 */
data class WaveformSample(
    /** 캡처 시작 기준 경과 시간(ms). 파형을 시간축에 정렬하기 위한 상대 시각. */
    val elapsedMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    /** sqrt(x²+y²+z²) — 현재 감지기 입력의 원본(저역통과 전). */
    val magnitude: Float,
    /** 저역통과 필터 후 값 = 감지기가 실제로 피크 판정에 쓰는 입력. */
    val filteredMagnitude: Float,
    /** 이 샘플 처리 직후의 보행 상태(IDLE/WALKING). */
    val state: WalkingStepDetectorLogic.State,
    /** 이 샘플 처리 직후의 누적 걸음 수. */
    val count: Int,
    /** 이 샘플에서 걸음이 새로 카운트됐는가 — 앉기 구간의 과다카운트 지점을 눈으로 짚기 위함. */
    val stepCounted: Boolean,
)

/**
 * 순수 CSV 직렬화. 스프레드시트·파이썬(pandas)에서 바로 열 수 있도록 표준 CSV 로 낸다.
 *
 * ⚠️ 실수 포맷은 **Locale.US 고정**이다. 기기 로케일이 소수점을 콤마(,)로 쓰면 CSV 열이 깨지므로
 *    반드시 점(.)으로 고정한다.
 */
object WaveformCsv {
    const val HEADER = "label,elapsed_ms,x,y,z,magnitude,filtered_mag,state,count,step_counted"

    fun row(label: WaveformLabel, s: WaveformSample): String = String.format(
        Locale.US,
        "%s,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%s,%d,%d",
        label.id,
        s.elapsedMs,
        s.x, s.y, s.z,
        s.magnitude, s.filteredMagnitude,
        s.state.name,
        s.count,
        if (s.stepCounted) 1 else 0,
    )

    /** 헤더 + 각 샘플 행(개행 결합). 샘플이 없으면 헤더만 반환한다. */
    fun of(label: WaveformLabel, samples: List<WaveformSample>): String =
        buildString {
            append(HEADER)
            for (s in samples) {
                append('\n')
                append(row(label, s))
            }
        }
}

/**
 * 라벨 하나로 진행되는 한 번의 수집 세션 버퍼. 시작 시 라벨을 고정하고 이전 샘플을 비운다.
 * 녹화 중(add)에만 샘플이 쌓이고, 정지 후에도 버퍼는 유지돼(내보내기용) reset 으로만 비운다.
 */
class WaveformRecorder {
    var label: WaveformLabel = WaveformLabel.NORMAL_WALK
        private set

    private val _samples = mutableListOf<WaveformSample>()
    val samples: List<WaveformSample> get() = _samples

    var isRecording: Boolean = false
        private set

    val sampleCount: Int get() = _samples.size

    /** 새 수집 시작 — 라벨 고정 + 이전 버퍼 비움 + 녹화 on. */
    fun start(label: WaveformLabel) {
        this.label = label
        _samples.clear()
        isRecording = true
    }

    /** 녹화 중일 때만 샘플을 쌓는다(정지 상태의 이벤트는 무시). */
    fun add(sample: WaveformSample) {
        if (isRecording) _samples.add(sample)
    }

    /** 녹화만 멈춘다 — 버퍼는 내보내기 위해 남겨둔다. */
    fun stop() {
        isRecording = false
    }

    /** 버퍼까지 완전 초기화(다음 수집 대비). */
    fun reset() {
        isRecording = false
        _samples.clear()
    }

    fun toCsv(): String = WaveformCsv.of(label, _samples)
}
