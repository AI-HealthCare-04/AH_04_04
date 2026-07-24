package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #131 파형 수집 도구 순수 코어 테스트 — CSV 포맷·구조화 메타·phase/큐 마커·버퍼 상한과 레코더 상태 전이를 고정한다.
 * (실제 파형 수집·파일 IO·공유는 debug 소스셋 UI 소관, 실기기 QA 몫.)
 *
 * ⚠️ src/testDebug 소스셋 — 코어가 src/debug 에 있어 testDebugUnitTest 에서만 돈다(릴리스 변형과 분리).
 */
class WaveformCaptureTest {

    private val placement = PlacementSpec(
        id = "front_pocket_r_in", position = "front_pocket", side = "right",
        screenFacing = "in", topDirection = "up", foldState = "folded",
    )
    private val meta = CaptureMeta(trialId = "t1", deviceModel = "samsung SM-F766N", placement = placement)

    private fun sample(
        sensorElapsedMs: Long = 0L,
        callbackElapsedMs: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f,
        magnitude: Float = 0f, filtered: Float = 9.8f,
        state: WalkingStepDetectorLogic.State = WalkingStepDetectorLogic.State.IDLE,
        count: Int = 0, stepCounted: Boolean = false,
        hwStepCounter: Int = 0, hwStepDetected: Boolean = false, hwStepDetectorTotal: Int = 0,
    ) = WaveformSample(
        sensorElapsedMs, callbackElapsedMs, x, y, z, magnitude, filtered, state, count, stepCounted,
        hwStepCounter = hwStepCounter, hwStepDetected = hwStepDetected,
        hwStepDetectorTotal = hwStepDetectorTotal,
    )

    // ── CSV 포맷 ─────────────────────────────────────────────

    @Test
    fun header_columns_are_fixed() {
        assertEquals(
            "trial_id,device_model,placement_id,position,side,screen_facing,top_direction,fold_state," +
                "cue_delivery,label,phase,event,excluded,sensor_elapsed_ms,callback_elapsed_ms," +
                "x,y,z,magnitude,filtered_mag,state,count,step_counted," +
                "hw_step_counter,hw_step_detector,hw_step_detector_total",
            WaveformCsv.HEADER,
        )
    }

    @Test
    fun row_serializes_meta_placement_timestamps_label_phase_and_state_name() {
        val s = sample(
            sensorElapsedMs = 1200L, callbackElapsedMs = 1234L,
            x = 0.5f, y = -1.25f, z = 9.8f,
            magnitude = 9.9f, filtered = 10.5f,
            state = WalkingStepDetectorLogic.State.WALKING,
            count = 7, stepCounted = true,
            hwStepCounter = 6, hwStepDetected = true, hwStepDetectorTotal = 5,
        ).copy(phase = WaveformPhase.SITTING, event = "sit_cue", excluded = true)
        val row = WaveformCsv.row(meta.copy(cueDelivery = "success"), WaveformLabel.WALK_THEN_SIT, s)
        assertEquals(
            "t1,samsung SM-F766N,front_pocket_r_in,front_pocket,right,in,up,folded," +
                "success,walk_then_sit,sitting,sit_cue,1,1200,1234," +
                "0.50000,-1.25000,9.80000,9.90000,10.50000,WALKING,7,1,6,1,5",
            row,
        )
    }

    @Test
    fun step_counted_false_serializes_as_zero() {
        // 뒤 4필드 = step_counted, hw_step_counter, hw_step_detector, hw_step_detector_total (모두 0).
        val row = WaveformCsv.row(meta, WaveformLabel.NORMAL_WALK, sample(stepCounted = false))
        assertTrue("step_counted 는 0 이어야 한다: $row", row.endsWith(",0,0,0,0"))
    }

    @Test
    fun hw_step_columns_serialize_counter_detector_and_total() {
        // HW 만보기(#184/#176 하이브리드): 누적 카운터 + 스텝 감지 이벤트(0/1) + 감지기 누적 총계를 끝 세 컬럼에 기록.
        val row = WaveformCsv.row(
            meta, WaveformLabel.SHUFFLE,
            sample(hwStepCounter = 12, hwStepDetected = true, hwStepDetectorTotal = 9),
        )
        assertTrue("hw_step_counter=12, detector=1, total=9 로 직렬화: $row", row.endsWith(",12,1,9"))
    }

    // ── HW 만보기 순수 로직 (#194 블로커2: 측정불가 sentinel 구분) ──────────

    @Test
    fun hw_counter_reports_negative_sentinels_for_unavailable_states() {
        // 권한 거부 / 센서 부재 / 기준 미준비를 서로 다른 음수 sentinel 로 구분해 '실제 0걸음'과 가른다.
        assertEquals(
            WaveformHw.PERMISSION_DENIED,
            WaveformHw.counterSinceStart(permitted = false, sensorPresent = true, baseCount = 10, latestCount = 20),
        )
        assertEquals(
            WaveformHw.SENSOR_ABSENT,
            WaveformHw.counterSinceStart(permitted = true, sensorPresent = false, baseCount = 10, latestCount = 20),
        )
        assertEquals(
            WaveformHw.BASELINE_NOT_READY,
            WaveformHw.counterSinceStart(permitted = true, sensorPresent = true, baseCount = -1, latestCount = 20),
        )
        assertEquals(
            WaveformHw.BASELINE_NOT_READY,
            WaveformHw.counterSinceStart(permitted = true, sensorPresent = true, baseCount = 10, latestCount = -1),
        )
    }

    @Test
    fun hw_counter_returns_steps_since_start_when_available() {
        // 허용·센서 있고 기준 확보되면 '녹화 시작 기준 누적'(latest−base).
        assertEquals(
            12,
            WaveformHw.counterSinceStart(permitted = true, sensorPresent = true, baseCount = 1000, latestCount = 1012),
        )
        assertEquals(
            0,
            WaveformHw.counterSinceStart(permitted = true, sensorPresent = true, baseCount = 1000, latestCount = 1000),
        )
    }

    @Test
    fun finalize_hw_walk_end_overwrites_last_row_and_noops_on_empty() {
        val r = WaveformRecorder()
        r.finalizeHwWalkEnd(9, 4) // 빈 버퍼 → no-op (예외 없음)
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.add(sample(sensorElapsedMs = 0L, hwStepCounter = 3))
        r.add(sample(sensorElapsedMs = 20L, hwStepCounter = 5))
        r.stop()
        // 종료 후 flush 로 확정된 최종 HW(누적·감지기 총계)를 마지막 walking 행에 덮어쓴다(지연 이벤트 반영).
        r.finalizeHwWalkEnd(8, 7)
        assertEquals(3, r.samples[0].hwStepCounter) // 이전 행은 그대로
        assertEquals(0, r.samples[0].hwStepDetectorTotal)
        assertEquals(8, r.samples[1].hwStepCounter) // 마지막 walking 행만 최종값으로
        assertEquals(7, r.samples[1].hwStepDetectorTotal)
    }

    @Test
    fun finalize_hw_walk_end_targets_last_walking_row_not_trailing_sitting() {
        // walk_then_sit 은 trial 마지막 행이 phase=sitting 이고, 분석기는 sitting 을 빼고 '마지막 walking 행'을
        //   읽는다 → finalize 도 그 행에 써야 값이 정확도표에서 읽힌다(리뷰 #194 블로커2, walk_then_sit + 지연 counter).
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT, meta)
        r.add(sample(sensorElapsedMs = 0L, hwStepCounter = 2))   // walking
        r.add(sample(sensorElapsedMs = 20L, hwStepCounter = 4))  // walking (마지막 walking 행)
        r.markSitting()
        r.add(sample(sensorElapsedMs = 1000L)) // sitting (trial 마지막 행)
        r.stop()
        r.finalizeHwWalkEnd(6, 5) // 보행 종료 시점 누적을 flush 로 확정
        assertEquals(WaveformPhase.WALKING, r.samples[1].phase)
        assertEquals(6, r.samples[1].hwStepCounter)       // 마지막 walking 행에 확정
        assertEquals(5, r.samples[1].hwStepDetectorTotal)
        assertEquals(WaveformPhase.SITTING, r.samples[2].phase)
        assertEquals(0, r.samples[2].hwStepCounter)       // 뒤따르는 sitting 행은 건드리지 않음
    }

    @Test
    fun attributes_to_walk_only_within_frozen_walk_end() {
        // 보행 종료 시점(walkEnd) 이내 이벤트만 참조에 귀속 — 종료 후 정착 중 걸음(ts>walkEnd)은 제외(블로커3).
        assertTrue(WaveformHw.attributesToWalk(eventTsNs = 900, walkEndNs = 1000))
        assertTrue(WaveformHw.attributesToWalk(eventTsNs = 1000, walkEndNs = 1000)) // 경계 포함
        assertFalse(WaveformHw.attributesToWalk(eventTsNs = 1200, walkEndNs = 1000)) // 종료 후
        assertFalse(WaveformHw.attributesToWalk(eventTsNs = 900, walkEndNs = -1))     // walkEnd 미확정
    }

    @Test
    fun detector_total_reports_sentinels_when_unavailable() {
        // 감지기 총계도 counter 와 같은 sentinel 로 '측정 불가'를 '실제 0'과 구분(BASELINE 은 없음 — 0 도 유효).
        assertEquals(
            WaveformHw.PERMISSION_DENIED,
            WaveformHw.detectorTotal(permitted = false, sensorPresent = true, count = 5),
        )
        assertEquals(
            WaveformHw.SENSOR_ABSENT,
            WaveformHw.detectorTotal(permitted = true, sensorPresent = false, count = 5),
        )
        assertEquals(0, WaveformHw.detectorTotal(permitted = true, sensorPresent = true, count = 0))
        assertEquals(5, WaveformHw.detectorTotal(permitted = true, sensorPresent = true, count = 5))
    }

    @Test
    fun base_at_start_only_accepts_events_at_or_before_start() {
        // 시작 시각 이내(ts≤start) 최신 누적만 base 로 인정 — 준비 동작 걸음을 base 에 넣어 최종 diff 에서 상쇄(블로커3).
        assertEquals(100L, WaveformHw.baseAtStart(latestValue = 100, latestTsNs = 5, startNs = 10)) // 시작 전 이벤트
        assertEquals(100L, WaveformHw.baseAtStart(latestValue = 100, latestTsNs = 10, startNs = 10)) // 경계 포함
        assertEquals(-1L, WaveformHw.baseAtStart(latestValue = 100, latestTsNs = 15, startNs = 10))  // 시작 후 → 아직 base 아님
        assertEquals(-1L, WaveformHw.baseAtStart(latestValue = -1, latestTsNs = 5, startNs = 10))    // 최신값 없음
        assertEquals(-1L, WaveformHw.baseAtStart(latestValue = 100, latestTsNs = 5, startNs = -1))   // 시작 미확정
    }

    @Test
    fun resolve_walk_end_falls_back_to_base_so_real_zero_is_zero_not_sentinel() {
        // 보행 종료 이내 counter 이벤트가 없었으면(-1) 유효 base 로 폴백 → 실제 0걸음은 sentinel 이 아니라 0 (블로커1).
        assertEquals(50L, WaveformHw.resolveWalkEndCount(walkEndCount = 50, baseCount = 100)) // 이벤트 있으면 그대로
        assertEquals(100L, WaveformHw.resolveWalkEndCount(walkEndCount = -1, baseCount = 100)) // 없으면 base 폴백
        assertEquals(-1L, WaveformHw.resolveWalkEndCount(walkEndCount = -1, baseCount = -1))   // base 도 없으면 -1 유지
        // base 유효 + walkEnd 이벤트 0 → 최종 counter 는 0 (BASELINE_NOT_READY 아님).
        val base = 100L
        assertEquals(
            0,
            WaveformHw.counterSinceStart(
                permitted = true, sensorPresent = true,
                baseCount = base, latestCount = WaveformHw.resolveWalkEndCount(-1L, base),
            ),
        )
        // base 도 미확보면 종료 정산도 측정 불가로 정직하게 남는다.
        assertEquals(
            WaveformHw.BASELINE_NOT_READY,
            WaveformHw.counterSinceStart(
                permitted = true, sensorPresent = true,
                baseCount = -1L, latestCount = WaveformHw.resolveWalkEndCount(-1L, -1L),
            ),
        )
    }

    @Test
    fun finalize_hw_walk_end_records_zero_for_real_zero_step_walk() {
        // 실기기 시나리오: base 유효(100)·보행 중 새 counter 이벤트 0(HW 과소계수/실제 0) → 마지막 walking 행 = 0.
        val r = WaveformRecorder()
        r.start(WaveformLabel.SHUFFLE, meta)
        r.add(sample(sensorElapsedMs = 0L))
        r.add(sample(sensorElapsedMs = 20L))
        r.stop()
        val base = 100L
        r.finalizeHwWalkEnd(
            counter = WaveformHw.counterSinceStart(
                permitted = true, sensorPresent = true,
                baseCount = base, latestCount = WaveformHw.resolveWalkEndCount(-1L, base),
            ),
            detectorTotal = WaveformHw.detectorTotal(permitted = true, sensorPresent = true, count = 0),
        )
        assertEquals(0, r.samples[1].hwStepCounter)        // -3 이 아니라 0
        assertEquals(0, r.samples[1].hwStepDetectorTotal)
    }

    @Test
    fun meta_commas_are_sanitized_to_spaces() {
        // placement/device 등에 콤마가 섞여도 열이 밀리지 않게 공백으로 치환한다.
        val dirty = meta.copy(trialId = "t,1", deviceModel = "a,b")
        val row = WaveformCsv.row(dirty, WaveformLabel.NORMAL_WALK, sample())
        assertTrue("trial_id/device 콤마 제거: $row", row.startsWith("t 1,a b,front_pocket_r_in,"))
    }

    @Test
    fun decimal_separator_is_dot_regardless_of_default_locale() {
        // 콤마 소수점을 쓰는 로케일(예: 독일)에서도 CSV 가 깨지지 않도록 점(.)으로 고정한다.
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = WaveformCsv.row(meta, WaveformLabel.NORMAL_WALK, sample(x = 1.5f))
            assertTrue("소수점은 점이어야 한다: $row", row.contains("1.50000"))
            assertFalse("콤마 소수점이 섞이면 안 된다: $row", row.contains("1,50000"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun of_empty_samples_is_header_only() {
        assertEquals(WaveformCsv.HEADER, WaveformCsv.of(meta, WaveformLabel.NORMAL_WALK, emptyList()))
    }

    @Test
    fun of_prepends_header_then_one_line_per_sample() {
        val csv = WaveformCsv.of(
            meta,
            WaveformLabel.NORMAL_WALK,
            listOf(
                sample(sensorElapsedMs = 0L).copy(phase = WaveformPhase.WALKING),
                sample(sensorElapsedMs = 20L).copy(phase = WaveformPhase.WALKING),
            ),
        )
        val lines = csv.split("\n")
        assertEquals(3, lines.size) // 헤더 + 2행
        assertEquals(WaveformCsv.HEADER, lines[0])
        assertTrue(lines[1].contains(",normal_walk,walking,,0,0,"))
        assertTrue(lines[2].contains(",normal_walk,walking,,0,20,"))
    }

    // ── 레코더 상태 ──────────────────────────────────────────

    @Test
    fun start_sets_label_meta_clears_buffer_and_records() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.add(sample(sensorElapsedMs = 1L))
        val meta2 = meta.copy(trialId = "t2")
        r.start(WaveformLabel.WALK_THEN_SIT, meta2) // 재시작 → 이전 버퍼 비움 + 라벨/메타 교체
        assertEquals(WaveformLabel.WALK_THEN_SIT, r.label)
        assertEquals(meta2, r.meta)
        assertEquals(WaveformPhase.WALKING, r.phase) // 재시작 시 구간 복귀
        assertTrue(r.isRecording)
        assertEquals(0, r.sampleCount)
    }

    @Test
    fun add_appends_only_while_recording() {
        val r = WaveformRecorder()
        r.add(sample()) // 시작 전 → 무시
        assertEquals(0, r.sampleCount)
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.add(sample())
        r.add(sample())
        assertEquals(2, r.sampleCount)
        r.stop()
        r.add(sample()) // 정지 후 → 무시
        assertEquals(2, r.sampleCount)
    }

    @Test
    fun mark_sitting_stamps_cue_event_phase_and_exclusion_window() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT, meta)
        r.add(sample(sensorElapsedMs = 0L)) // WALKING, event 없음
        r.markSitting()
        r.add(sample(sensorElapsedMs = 1000L)) // 큐 직후 첫 샘플 → sit_cue + excluded
        r.add(sample(sensorElapsedMs = 1200L)) // 큐+200ms ≤ 300 → 아직 배제 구간
        r.add(sample(sensorElapsedMs = 1400L)) // 큐+400ms > 300 → 배제 아님

        assertEquals(WaveformPhase.WALKING, r.samples[0].phase)
        assertEquals("", r.samples[0].event)
        assertFalse(r.samples[0].excluded)

        assertEquals(WaveformPhase.SITTING, r.samples[1].phase)
        assertEquals("sit_cue", r.samples[1].event) // 큐 마커는 직후 첫 샘플에만
        assertTrue(r.samples[1].excluded)

        assertEquals("", r.samples[2].event)
        assertTrue("큐+200ms 는 배제 구간", r.samples[2].excluded)

        assertFalse("큐+400ms 는 배제 구간 밖", r.samples[3].excluded)

        // 큐가 전달됐음을 메타에 남긴다(비프 전달 성공 후에만 markSitting 호출되므로).
        assertEquals("success", r.meta.cueDelivery)
    }

    @Test
    fun buffer_caps_and_auto_stops_at_max_samples() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK, meta)
        repeat(WaveformRecorder.MAX_SAMPLES + 5) { r.add(sample(sensorElapsedMs = it.toLong())) }
        assertEquals(WaveformRecorder.MAX_SAMPLES, r.sampleCount) // 상한에서 멈춤
        assertFalse(r.isRecording) // 자동 정지
        assertTrue(r.stoppedByCap) // 상한 도달로 정지했음을 UI 가 구분 가능
    }

    @Test
    fun stop_keeps_buffer_for_export() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT, meta)
        r.add(sample(sensorElapsedMs = 5L))
        r.stop()
        assertFalse(r.isRecording)
        assertEquals(1, r.sampleCount) // 정지해도 내보내기용으로 유지
        assertTrue(r.toCsv().contains(",walk_then_sit,walking,,0,5,"))
    }

    // ── 라벨 계약 (#131 2단계: 대조군 2종 추가) ──────────────

    @Test
    fun labels_expose_stable_ids_and_cue_protocol_flags() {
        // CSV·분석 스크립트가 의존하는 라벨 id 와, 수집 화면 상태머신이 분기하는 큐 여부를 함께 고정한다.
        assertEquals("normal_walk", WaveformLabel.NORMAL_WALK.id)
        assertEquals("walk_then_sit", WaveformLabel.WALK_THEN_SIT.id)
        assertEquals("sit_only", WaveformLabel.SIT_ONLY.id)
        // 발 끌면서 걷기 = 신 id 'shuffle_walk'(구 'shuffle'=제자리 비보행과 의미 분리, 리뷰 #194).
        assertEquals("shuffle_walk", WaveformLabel.SHUFFLE.id)

        // 앉기 큐(비프→SITTING)를 쓰는 건 '앉기'가 포함된 두 라벨뿐 — 대조군(정상 보행·제자리)은 큐가 없다.
        assertTrue(WaveformLabel.WALK_THEN_SIT.hasSitCue)
        assertTrue(WaveformLabel.SIT_ONLY.hasSitCue)
        assertFalse(WaveformLabel.NORMAL_WALK.hasSitCue)
        assertFalse(WaveformLabel.SHUFFLE.hasSitCue)
    }

    @Test
    fun sit_only_marks_the_boundary_with_the_same_cue_path_as_walk_then_sit() {
        // SIT_ONLY 는 '서있기 기준선' 뒤 앉기 큐로 경계를 남긴다 — WALK_THEN_SIT 과 동일한 레코더 경로.
        val r = WaveformRecorder()
        r.start(WaveformLabel.SIT_ONLY, meta)
        r.add(sample(sensorElapsedMs = 0L)) // 서있기 기준선(WALKING 구간)
        r.markSitting()
        r.add(sample(sensorElapsedMs = 1000L)) // 큐 직후 첫 샘플 → sit_cue + SITTING
        assertEquals(WaveformLabel.SIT_ONLY, r.label)
        assertEquals(WaveformPhase.WALKING, r.samples[0].phase)
        assertEquals("sit_cue", r.samples[1].event)
        assertEquals(WaveformPhase.SITTING, r.samples[1].phase)
        assertTrue("sit_only 라벨 id 직렬화", r.toCsv().contains(",sit_only,"))
    }

    @Test
    fun shuffle_serializes_with_its_own_label_id() {
        // 발 끌면서 걷기(저진폭 보행) — 큐 없이 전 구간 WALKING 으로만 남는다(#176 과소계수 회복 검증 라벨).
        val row = WaveformCsv.row(meta, WaveformLabel.SHUFFLE, sample())
        assertTrue("shuffle_walk 라벨 id·구간: $row", row.contains(",shuffle_walk,walking,"))
    }

    @Test
    fun reset_clears_buffer_recording_phase_and_cue() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.markSitting()
        r.add(sample())
        r.reset()
        assertFalse(r.isRecording)
        assertEquals(WaveformPhase.WALKING, r.phase)
        assertEquals(0, r.sampleCount)
        assertFalse(r.stoppedByCap)
        assertEquals(WaveformCsv.HEADER, WaveformCsv.of(CaptureMeta.UNKNOWN, r.label, r.samples))
    }
}
