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
 *   - Android 의존이 없어 단위 테스트로 포맷·상태 전이를 고정한다.
 *
 *  ⚠️ **debug 소스셋 전용**(리뷰 #150, menteur-Ran ②). 순수 코드지만 릴리스 APK 에는 포함되지 않도록
 *     수집 UI(WaveformCaptureScreen/Activity/Exporter)와 같은 debug 소스셋에 둔다. 테스트는
 *     `src/testDebug` 에 두어 testDebugUnitTest 에서만 돈다(릴리스 변형과 완전 분리).
 *
 *  데이터 계약(리뷰 #150 반영):
 *   - **phase + event**: WALK_THEN_SIT 은 '기기 무접촉 자동 큐'(수집 UI 의 카운트다운→비프)로 앉기 신호를
 *     내고, 그 순간을 `event=sit_cue` 로 CSV 에 남긴다(=보행/앉기 경계의 ground truth). 화면 터치가 아니라
 *     자동 타이머가 마커를 찍으므로 주머니/가방 배치에서도 파형이 오염되지 않는다(Earthworm-jk 블로커1).
 *   - **excluded**: 큐 직후 짧은 정착 구간(비프·자세전환)을 배제하도록 표시 → 학습에서 제외 가능.
 *   - **두 타임스탬프**: `sensorElapsedMs`(SensorEvent.timestamp 기준, 첫 샘플=0)를 기본 시간축으로,
 *     `callbackElapsedMs`(콜백 도착)를 함께 남겨 스케줄링 지터를 진단한다.
 *   - **구조화 placement**: 위치뿐 아니라 좌/우·화면 방향·상단 방향·폴더블 접힘까지 매 행에 남겨,
 *     서로 다른 시험의 x/y/z 를 비교 가능하게 한다(Earthworm-jk 블로커2).
 *
 *  ⚠️ 이 단계는 **데이터 수집 도구**까지다. 판별 특징·임계값·상태 머신 변경은 수집된 실측 파형으로 탐색한
 *     뒤 후속 단계에서 설계한다(현 PR 범위 밖). 감지기 로직은 건드리지 않는다.
 * ============================================================================
 */

/**
 * 캡처 시나리오 라벨 — 여러 동작을 대조해 '보행 vs 보행 직후 앉기' 판별 특징을 찾기 위해 파일·행에 함께 기록한다.
 *
 * 두 축으로 나뉜다:
 *  - **hasSitCue**: 앉기 큐(비프→markSitting)로 SITTING 구간 경계를 남기는 시나리오인가. '앉기'가 포함된
 *    라벨(WALK_THEN_SIT·SIT_ONLY)만 true 다. false 인 라벨은 큐 없이 고정 길이로만 수집한다.
 *  - **대조 목적**: NORMAL_WALK/SHUFFLE 은 오탐을 '일으키지 않아야' 하는 대조군(정상 보행·제자리 발끌기),
 *    WALK_THEN_SIT 은 오탐 대상(§5-5), SIT_ONLY 는 보행 momentum 없는 순수 앉기 하강 신호의 기준선이다.
 *
 * @property id CSV·분석 스크립트가 의존하는 안정 키(변경 금지).
 * @property display 화면 표시 이름.
 * @property hasSitCue 앉기 큐 프로토콜 사용 여부(수집 화면의 상태머신 분기 기준).
 * @property actionHint 큐 전(또는 전체) 구간에 사용자가 할 동작 — 화면 카운트다운 문구에 그대로 쓴다.
 */
enum class WaveformLabel(
    val id: String,
    val display: String,
    val hasSitCue: Boolean,
    val actionHint: String,
) {
    /** 정상 보행만(대조군) — 오탐이 나면 안 되는 기준. */
    NORMAL_WALK("normal_walk", "정상 보행", hasSitCue = false, actionHint = "계속 걸으세요"),

    /** 정상 보행 직후 곧바로 앉기(오탐 대상, §5-5). */
    WALK_THEN_SIT("walk_then_sit", "보행 직후 앉기", hasSitCue = true, actionHint = "계속 걸으세요"),

    /** 서 있다가 앉기만(보행 momentum 없는 순수 앉기 하강) — 앉기 신호 자체의 기준선. */
    SIT_ONLY("sit_only", "서서 앉기만", hasSitCue = true, actionHint = "그대로 서 계세요"),

    /** 제자리 발끌기(작은 진폭의 애매한 움직임, 대조군) — 오탐이 나면 안 되는 기준. */
    SHUFFLE("shuffle", "제자리 발끌기", hasSitCue = false, actionHint = "제자리 발끌기를 계속하세요"),
}

/**
 * 한 파일 안의 동작 구간 = '큐 전 활동 구간' vs '큐 후 착석 구간'의 ground truth.
 * 앉기 큐가 있는 라벨(WALK_THEN_SIT·SIT_ONLY)에서만 SITTING 으로 전이한다.
 *  - WALK_THEN_SIT: WALKING=보행 구간, SITTING=앉기 구간.
 *  - SIT_ONLY     : WALKING=**서있기 기준선** 구간(걷지 않음), SITTING=앉기 구간. 라벨로 구분되므로
 *                   phase.id 는 "활동/착석" 의미로 읽는다(=걷는다는 뜻이 아님).
 *  - NORMAL_WALK/SHUFFLE: 큐가 없어 전 구간 WALKING 으로만 남는다.
 */
enum class WaveformPhase(val id: String) {
    /** 큐 전 활동 구간(보행·서있기·제자리 등, 기본). */
    WALKING("walking"),

    /** 자동 큐(sit_cue) 이후 — 착석 전이 구간. */
    SITTING("sitting"),
}

/**
 * 착용 위치·방향 규격. 센서 좌표계는 기기 자연방향 기준이라, 같은 '앞주머니'라도 좌/우·화면 안밖·상단
 * 상하·폴더블 접힘에 따라 x/y/z 가 달라진다. 시험 간 축값을 비교하려면 이 규격이 매 행에 남아야 한다.
 * (해당 없음은 "na".)
 */
data class PlacementSpec(
    /** 규격 전체를 식별하는 ID(프리셋 키). */
    val id: String,
    /** 위치: front_pocket/back_pocket/hand/bag 등. */
    val position: String,
    /** 좌/우: left/right/na. */
    val side: String,
    /** 화면이 향하는 쪽: in(몸쪽)/out(바깥)/user/na. */
    val screenFacing: String,
    /** 상단(수화구쪽)이 향하는 방향: up/down/na. */
    val topDirection: String,
    /** 폴더블 접힘 상태: folded/unfolded/na. */
    val foldState: String,
) {
    companion object {
        val UNKNOWN = PlacementSpec("unknown", "unknown", "na", "na", "na", "na")
    }
}

/**
 * 한 번의 수집 세션에 붙는 메타데이터 — 매 CSV 행에 함께 남긴다.
 */
data class CaptureMeta(
    /** 이 수집을 유일하게 식별(파일명과 동일). ms 정밀이라 빠른 반복 수집에서 파일이 덮어써지지 않는다. */
    val trialId: String,
    /** Build.MANUFACTURER + MODEL. */
    val deviceModel: String,
    /** 착용 위치·방향 규격. */
    val placement: PlacementSpec,
    /**
     * 앉기 큐(비프) 전달 상태 — "na"(큐 없는 NORMAL_WALK) / "pending"(큐 전 정지) / "success"(큐 전달됨).
     * 큐가 실제로 들리지 않았는데 SITTING 으로 라벨링된 파일을 분석에서 걸러내기 위함(Earthworm-jk 블로커).
     */
    val cueDelivery: String = "na",
) {
    companion object {
        val UNKNOWN = CaptureMeta("unknown", "unknown", PlacementSpec.UNKNOWN)
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
    /** 레코더가 새기는 동작 구간(WALKING/SITTING). */
    val phase: WaveformPhase = WaveformPhase.WALKING,
    /** 레코더가 새기는 이벤트 마커("" 또는 "sit_cue"). 자동 큐가 발생한 정확한 샘플에 기록. */
    val event: String = "",
    /** 큐 직후 정착 구간(비프·자세전환)이라 학습에서 배제 권장인가. */
    val excluded: Boolean = false,
)

/**
 * 순수 CSV 직렬화. 스프레드시트·파이썬(pandas)에서 바로 열 수 있도록 표준 CSV 로 낸다.
 *
 * ⚠️ 실수 포맷은 **Locale.US 고정**이다. 기기 로케일이 소수점을 콤마(,)로 쓰면 CSV 열이 깨지므로
 *    반드시 점(.)으로 고정한다. 메타 문자열의 콤마/개행은 열이 밀리지 않게 공백으로 치환한다.
 */
object WaveformCsv {
    const val HEADER =
        "trial_id,device_model,placement_id,position,side,screen_facing,top_direction,fold_state," +
            "cue_delivery,label,phase,event,excluded,sensor_elapsed_ms,callback_elapsed_ms," +
            "x,y,z,magnitude,filtered_mag,state,count,step_counted"

    /** CSV 셀 안의 콤마·개행을 공백으로 치환(따옴표 이스케이프 없이 단순 CSV 유지). */
    private fun cell(s: String): String = s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ')

    fun row(meta: CaptureMeta, label: WaveformLabel, s: WaveformSample): String {
        val p = meta.placement
        return String.format(
            Locale.US,
            "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%s,%d,%d",
            cell(meta.trialId), cell(meta.deviceModel),
            cell(p.id), cell(p.position), cell(p.side), cell(p.screenFacing),
            cell(p.topDirection), cell(p.foldState),
            cell(meta.cueDelivery),
            label.id, s.phase.id, cell(s.event), if (s.excluded) 1 else 0,
            s.sensorElapsedMs, s.callbackElapsedMs,
            s.x, s.y, s.z,
            s.magnitude, s.filteredMagnitude,
            s.state.name,
            s.count,
            if (s.stepCounted) 1 else 0,
        )
    }

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
 * 앉기 큐(markSitting)는 phase 를 SITTING 으로 전이하고, 그 **직후 첫 샘플**에 `event=sit_cue` 를 찍은 뒤
 * 짧은 정착 구간을 excluded 로 표시한다 — 큐 시각(=ground truth)을 add 시점에 단일 소스로 확정한다.
 * 버퍼는 MAX_SAMPLES 에서 자동 정지해, 정지를 잊고 방치해도 toCsv() OOM 으로 수집분을 잃지 않는다(menteur-Ran ①).
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

    /** 상한 도달로 자동 정지됐는가(정상 정지와 구분해 UI 안내용). */
    var stoppedByCap: Boolean = false
        private set

    val sampleCount: Int get() = _samples.size

    // 큐 상태: markSitting 이 pendingCue 를 세우면 다음 add 가 sit_cue 를 찍고 cueSensorMs 를 확정한다.
    private var pendingCue = false
    private var cueSensorMs = -1L

    /** 새 수집 시작 — 라벨·메타 고정 + 구간 WALKING 복귀 + 큐 상태 초기화 + 이전 버퍼 비움 + 녹화 on. */
    fun start(label: WaveformLabel, meta: CaptureMeta) {
        this.label = label
        this.meta = meta
        phase = WaveformPhase.WALKING
        pendingCue = false
        cueSensorMs = -1L
        stoppedByCap = false
        _samples.clear()
        isRecording = true
    }

    /**
     * 녹화 중일 때만 샘플을 쌓는다(정지 상태의 이벤트는 무시). 넘겨받은 phase/event/excluded 는 무시하고
     * **레코더가 계산한 값으로 고정**해 저장(단일 소스). 상한(MAX_SAMPLES) 도달 시 자동 정지한다.
     */
    fun add(sample: WaveformSample) {
        if (!isRecording) return
        if (_samples.size >= MAX_SAMPLES) {
            isRecording = false
            stoppedByCap = true
            return
        }
        var event = ""
        if (pendingCue) {
            event = EVENT_SIT_CUE
            cueSensorMs = sample.sensorElapsedMs
            pendingCue = false
        }
        val excluded = cueSensorMs >= 0L &&
            (sample.sensorElapsedMs - cueSensorMs) in 0L..CUE_EXCLUSION_MS
        _samples.add(sample.copy(phase = phase, event = event, excluded = excluded))
    }

    /**
     * 앉기 큐 — 기기 무접촉 자동 타이머가 **비프 전달 성공을 확인한 뒤에만** 호출한다(화면 터치 아님).
     * 이후 add 되는 샘플이 SITTING 구간이 되고, 큐 직후 첫 샘플에 event=sit_cue + 정착 구간 excluded 가 찍힌다.
     * 큐가 실제 전달됐음을 메타(cue_delivery=success)에 남긴다.
     */
    fun markSitting() {
        phase = WaveformPhase.SITTING
        pendingCue = true
        meta = meta.copy(cueDelivery = CUE_DELIVERY_SUCCESS)
    }

    /** 녹화만 멈춘다 — 버퍼는 내보내기 위해 남겨둔다. */
    fun stop() {
        isRecording = false
    }

    /** 버퍼까지 완전 초기화(다음 수집 대비). */
    fun reset() {
        isRecording = false
        phase = WaveformPhase.WALKING
        pendingCue = false
        cueSensorMs = -1L
        stoppedByCap = false
        _samples.clear()
    }

    fun toCsv(): String = WaveformCsv.of(meta, label, _samples)

    companion object {
        const val EVENT_SIT_CUE = "sit_cue"

        /** cue_delivery 메타값 — 큐 비프가 실제 전달됐음. */
        const val CUE_DELIVERY_SUCCESS = "success"

        /** 큐 직후 배제 구간(ms) — 비프·자세전환의 정착 시간. */
        const val CUE_EXCLUSION_MS = 300L

        /** 버퍼 상한(약 50Hz × 10분). 방치 시 자동 정지해 toCsv() OOM 을 막는다. */
        const val MAX_SAMPLES = 30_000
    }
}
