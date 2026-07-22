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
 *     걸음 카운트 여부)을 한 행으로 묶어 라벨·구간(phase)·수집 메타와 함께 CSV 로 직렬화한다.
 *   - Android 의존이 없어 JVM 단위 테스트로 포맷·상태 전이를 고정한다(수집 UI/파일 IO 는 debug 소스셋).
 *
 *  데이터 계약(리뷰 #150 반영):
 *   - **phase**: 한 파일 안에서 '보행 구간(WALKING)'과 '앉기 구간(SITTING)'을 나눈다. 수집자가 앉기
 *     직전에 [앉기 시작]을 누르면 그 이후 샘플이 SITTING 으로 바뀌어, 2단계 학습·정렬의 ground truth 가 된다.
 *   - **두 타임스탬프**: `sensorElapsedMs`(SensorEvent.timestamp 기준, 첫 샘플=0)를 기본 시간축으로 쓰고,
 *     `callbackElapsedMs`(콜백 도착 시각)를 함께 남겨 스케줄링 지터를 진단할 수 있게 한다.
 *   - **수집 메타**: trial_id·device_model·placement 를 매 행에 남겨 서로 다른 시험의 x/y/z 를 비교 가능하게 한다.
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
 * 한 파일 안의 동작 구간. WALK_THEN_SIT 수집에서 '어디까지 걷고 어디부터 앉았는지'를 표시하는 ground truth.
 * (NORMAL_WALK 은 전 구간 WALKING 으로만 남는다.)
 */
enum class WaveformPhase(val id: String) {
    /** 걷는 구간(기본). */
    WALKING("walking"),

    /** 수집자가 [앉기 시작]을 누른 이후 — 착석 전이 구간. */
    SITTING("sitting"),
}

/**
 * 한 번의 수집 세션에 붙는 메타데이터. 서로 다른 시험의 x/y/z 축값을 비교하려면 기기·착용 위치가
 * 동일/명시돼야 하므로(센서 좌표계는 기기 자연방향 기준), 매 CSV 행에 함께 남긴다.
 */
data class CaptureMeta(
    /** 이 수집을 유일하게 식별(파일명과 동일). 빠른 반복 수집에서 파일이 덮어써지지 않게 ms 정밀도 권장. */
    val trialId: String,
    /** Build.MODEL 등 기기 모델명. */
    val deviceModel: String,
    /** 착용 위치·방향(예: 앞주머니/손/가방). 폴더블 접힘 상태 등도 여기에 표기. */
    val placement: String,
) {
    companion object {
        val UNKNOWN = CaptureMeta("unknown", "unknown", "unknown")
    }
}

/**
 * 가속도 샘플 1개 + 그 순간 감지기 상태의 스냅샷.
 * 원시 3축(x,y,z)은 특징 탐색의 핵심이고, 파생값은 '감지기가 어디서 오탐하는지'를 같은 행에서 보기 위함이다.
 */
data class WaveformSample(
    /** SensorEvent.timestamp 기준 경과 시간(ms) — 첫 샘플=0. **기본 시간축**(콜백 지터에 오염되지 않음). */
    val sensorElapsedMs: Long,
    /** 콜백 도착(elapsedRealtime) 기준 경과 시간(ms) — 첫 샘플=0. 센서 시각과의 차이로 지터를 진단. */
    val callbackElapsedMs: Long,
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
    /** 수집자가 표시한 동작 구간(WALKING/SITTING) — 보행/앉기 정렬의 ground truth. */
    val phase: WaveformPhase,
)

/**
 * 순수 CSV 직렬화. 스프레드시트·파이썬(pandas)에서 바로 열 수 있도록 표준 CSV 로 낸다.
 *
 * ⚠️ 실수 포맷은 **Locale.US 고정**이다. 기기 로케일이 소수점을 콤마(,)로 쓰면 CSV 열이 깨지므로
 *    반드시 점(.)으로 고정한다. 메타 문자열의 콤마/개행은 열이 밀리지 않게 공백으로 치환한다.
 */
object WaveformCsv {
    const val HEADER =
        "trial_id,device_model,placement,label,phase," +
            "sensor_elapsed_ms,callback_elapsed_ms,x,y,z,magnitude,filtered_mag,state,count,step_counted"

    /** CSV 셀 안의 콤마·개행을 공백으로 치환(따옴표 이스케이프 없이 단순 CSV 유지). */
    private fun cell(s: String): String = s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ')

    fun row(meta: CaptureMeta, label: WaveformLabel, s: WaveformSample): String = String.format(
        Locale.US,
        "%s,%s,%s,%s,%s,%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%s,%d,%d",
        cell(meta.trialId),
        cell(meta.deviceModel),
        cell(meta.placement),
        label.id,
        s.phase.id,
        s.sensorElapsedMs,
        s.callbackElapsedMs,
        s.x, s.y, s.z,
        s.magnitude, s.filteredMagnitude,
        s.state.name,
        s.count,
        if (s.stepCounted) 1 else 0,
    )

    /** 헤더 + 각 샘플 행(개행 결합). 샘플이 없으면 헤더만 반환한다. */
    fun of(meta: CaptureMeta, label: WaveformLabel, samples: List<WaveformSample>): String =
        buildString {
            append(HEADER)
            for (s in samples) {
                append('\n')
                append(row(meta, label, s))
            }
        }
}

/**
 * 라벨 하나로 진행되는 한 번의 수집 세션 버퍼. 시작 시 라벨·메타를 고정하고 이전 샘플을 비운다.
 * 녹화 중(add)에만 샘플이 쌓이고, 정지 후에도 버퍼는 유지돼(내보내기용) reset 으로만 비운다.
 *
 * 구간(phase)은 시작 시 WALKING 으로 두고, [앉기 시작] 시 markSitting() 으로 전이한다.
 * add() 는 add 시점의 현재 phase 를 샘플에 새겨(=수집자가 실시간으로 찍은 ground truth), 이후 정렬 기준이 된다.
 */
class WaveformRecorder {
    var label: WaveformLabel = WaveformLabel.NORMAL_WALK
        private set

    var meta: CaptureMeta = CaptureMeta.UNKNOWN
        private set

    var phase: WaveformPhase = WaveformPhase.WALKING
        private set

    private val _samples = mutableListOf<WaveformSample>()
    val samples: List<WaveformSample> get() = _samples

    var isRecording: Boolean = false
        private set

    val sampleCount: Int get() = _samples.size

    /** 새 수집 시작 — 라벨·메타 고정 + 구간 WALKING 복귀 + 이전 버퍼 비움 + 녹화 on. */
    fun start(label: WaveformLabel, meta: CaptureMeta) {
        this.label = label
        this.meta = meta
        phase = WaveformPhase.WALKING
        _samples.clear()
        isRecording = true
    }

    /**
     * 녹화 중일 때만 샘플을 쌓는다(정지 상태의 이벤트는 무시).
     * 넘겨받은 샘플의 phase 는 무시하고 **레코더의 현재 phase 로 고정**해 저장(단일 소스).
     */
    fun add(sample: WaveformSample) {
        if (isRecording) _samples.add(sample.copy(phase = phase))
    }

    /** 앉기 직전 표시 — 이후 add 되는 샘플이 SITTING 구간으로 기록된다(보행/앉기 경계). */
    fun markSitting() {
        phase = WaveformPhase.SITTING
    }

    /** 녹화만 멈춘다 — 버퍼는 내보내기 위해 남겨둔다. */
    fun stop() {
        isRecording = false
    }

    /** 버퍼까지 완전 초기화(다음 수집 대비). */
    fun reset() {
        isRecording = false
        phase = WaveformPhase.WALKING
        _samples.clear()
    }

    fun toCsv(): String = WaveformCsv.of(meta, label, _samples)
}
