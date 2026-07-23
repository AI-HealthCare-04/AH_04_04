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
    ) = WaveformSample(
        sensorElapsedMs, callbackElapsedMs, x, y, z, magnitude, filtered, state, count, stepCounted,
    )

    // ── CSV 포맷 ─────────────────────────────────────────────

    @Test
    fun header_columns_are_fixed() {
        assertEquals(
            "trial_id,device_model,placement_id,position,side,screen_facing,top_direction,fold_state," +
                "cue_delivery,label,phase,event,excluded,sensor_elapsed_ms,callback_elapsed_ms," +
                "x,y,z,magnitude,filtered_mag,state,count,step_counted",
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
        ).copy(phase = WaveformPhase.SITTING, event = "sit_cue", excluded = true)
        val row = WaveformCsv.row(meta.copy(cueDelivery = "success"), WaveformLabel.WALK_THEN_SIT, s)
        assertEquals(
            "t1,samsung SM-F766N,front_pocket_r_in,front_pocket,right,in,up,folded," +
                "success,walk_then_sit,sitting,sit_cue,1,1200,1234," +
                "0.50000,-1.25000,9.80000,9.90000,10.50000,WALKING,7,1",
            row,
        )
    }

    @Test
    fun step_counted_false_serializes_as_zero() {
        val row = WaveformCsv.row(meta, WaveformLabel.NORMAL_WALK, sample(stepCounted = false))
        assertTrue("step_counted 는 0 이어야 한다: $row", row.endsWith(",0"))
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
        assertEquals("shuffle", WaveformLabel.SHUFFLE.id)

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
        // 제자리 발끌기 — 큐 없이 전 구간 WALKING 으로만 남는다(대조군).
        val row = WaveformCsv.row(meta, WaveformLabel.SHUFFLE, sample())
        assertTrue("shuffle 라벨 id·구간: $row", row.contains(",shuffle,walking,"))
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
