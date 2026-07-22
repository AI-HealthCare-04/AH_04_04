package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #131 파형 수집 도구 순수 코어 테스트 — CSV 포맷·메타·phase 구간과 레코더 상태 전이를 고정한다.
 * (실제 파형 수집·파일 IO·공유는 debug 소스셋 UI 소관, 실기기 QA 몫.)
 */
class WaveformCaptureTest {

    private val meta = CaptureMeta(trialId = "t1", deviceModel = "SM-F766N", placement = "front_pocket")

    private fun sample(
        sensorElapsedMs: Long = 0L,
        callbackElapsedMs: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f,
        magnitude: Float = 0f, filtered: Float = 9.8f,
        state: WalkingStepDetectorLogic.State = WalkingStepDetectorLogic.State.IDLE,
        count: Int = 0, stepCounted: Boolean = false,
        phase: WaveformPhase = WaveformPhase.WALKING,
    ) = WaveformSample(
        sensorElapsedMs, callbackElapsedMs, x, y, z, magnitude, filtered, state, count, stepCounted, phase,
    )

    // ── CSV 포맷 ─────────────────────────────────────────────

    @Test
    fun header_columns_are_fixed() {
        assertEquals(
            "trial_id,device_model,placement,label,phase," +
                "sensor_elapsed_ms,callback_elapsed_ms,x,y,z,magnitude,filtered_mag,state,count,step_counted",
            WaveformCsv.HEADER,
        )
    }

    @Test
    fun row_serializes_meta_timestamps_label_phase_and_state_name() {
        val s = sample(
            sensorElapsedMs = 1200L, callbackElapsedMs = 1234L,
            x = 0.5f, y = -1.25f, z = 9.8f,
            magnitude = 9.9f, filtered = 10.5f,
            state = WalkingStepDetectorLogic.State.WALKING,
            count = 7, stepCounted = true, phase = WaveformPhase.SITTING,
        )
        val row = WaveformCsv.row(meta, WaveformLabel.WALK_THEN_SIT, s)
        assertEquals(
            "t1,SM-F766N,front_pocket,walk_then_sit,sitting," +
                "1200,1234,0.50000,-1.25000,9.80000,9.90000,10.50000,WALKING,7,1",
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
        // placement 등에 콤마가 섞여도 열이 밀리지 않게 공백으로 치환한다.
        val dirty = CaptureMeta(trialId = "t,1", deviceModel = "a,b", placement = "hand, left")
        val row = WaveformCsv.row(dirty, WaveformLabel.NORMAL_WALK, sample())
        assertTrue("trial_id 콤마 제거: $row", row.startsWith("t 1,a b,hand  left,normal_walk,"))
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
            listOf(sample(sensorElapsedMs = 0L), sample(sensorElapsedMs = 20L)),
        )
        val lines = csv.split("\n")
        assertEquals(3, lines.size) // 헤더 + 2행
        assertEquals(WaveformCsv.HEADER, lines[0])
        assertTrue(lines[1].startsWith("t1,SM-F766N,front_pocket,normal_walk,walking,0,"))
        assertTrue(lines[2].startsWith("t1,SM-F766N,front_pocket,normal_walk,walking,20,"))
    }

    // ── 레코더 상태 ──────────────────────────────────────────

    @Test
    fun start_sets_label_meta_clears_buffer_and_records() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.add(sample(sensorElapsedMs = 1L))
        val meta2 = CaptureMeta("t2", "SM-F766N", "hand")
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
    fun mark_sitting_stamps_subsequent_samples_as_sitting() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT, meta)
        r.add(sample()) // WALKING
        r.markSitting()
        r.add(sample()) // SITTING
        assertEquals(WaveformPhase.WALKING, r.samples[0].phase)
        assertEquals(WaveformPhase.SITTING, r.samples[1].phase)
        // add 는 넘어온 phase 를 무시하고 레코더 현재 phase 로 고정한다(단일 소스).
        val forced = r.samples[1]
        assertEquals(WaveformPhase.SITTING, forced.phase)
    }

    @Test
    fun stop_keeps_buffer_for_export() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT, meta)
        r.add(sample(sensorElapsedMs = 5L))
        r.stop()
        assertFalse(r.isRecording)
        assertEquals(1, r.sampleCount) // 정지해도 내보내기용으로 유지
        assertTrue(r.toCsv().contains(",walk_then_sit,walking,5,"))
    }

    @Test
    fun reset_clears_buffer_recording_and_phase() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK, meta)
        r.markSitting()
        r.add(sample())
        r.reset()
        assertFalse(r.isRecording)
        assertEquals(WaveformPhase.WALKING, r.phase)
        assertEquals(0, r.sampleCount)
        // 비면 헤더만(라벨/메타는 유지되지만 행이 없음).
        assertEquals(WaveformCsv.HEADER, WaveformCsv.of(CaptureMeta.UNKNOWN, r.label, r.samples))
    }
}
