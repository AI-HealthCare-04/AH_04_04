package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #131 파형 수집 도구 순수 코어 테스트 — CSV 포맷과 레코더 상태 전이를 고정한다.
 * (실제 파형 수집·파일 IO·공유는 debug 소스셋 UI 소관, 실기기 QA 몫.)
 */
class WaveformCaptureTest {

    private fun sample(
        elapsedMs: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f,
        magnitude: Float = 0f, filtered: Float = 9.8f,
        state: WalkingStepDetectorLogic.State = WalkingStepDetectorLogic.State.IDLE,
        count: Int = 0, stepCounted: Boolean = false,
    ) = WaveformSample(elapsedMs, x, y, z, magnitude, filtered, state, count, stepCounted)

    // ── CSV 포맷 ─────────────────────────────────────────────

    @Test
    fun header_columns_are_fixed() {
        assertEquals(
            "label,elapsed_ms,x,y,z,magnitude,filtered_mag,state,count,step_counted",
            WaveformCsv.HEADER,
        )
    }

    @Test
    fun row_serializes_all_fields_with_label_and_state_name() {
        val s = sample(
            elapsedMs = 1234L, x = 0.5f, y = -1.25f, z = 9.8f,
            magnitude = 9.9f, filtered = 10.5f,
            state = WalkingStepDetectorLogic.State.WALKING,
            count = 7, stepCounted = true,
        )
        val row = WaveformCsv.row(WaveformLabel.WALK_THEN_SIT, s)
        assertEquals(
            "walk_then_sit,1234,0.50000,-1.25000,9.80000,9.90000,10.50000,WALKING,7,1",
            row,
        )
    }

    @Test
    fun step_counted_false_serializes_as_zero() {
        val row = WaveformCsv.row(WaveformLabel.NORMAL_WALK, sample(stepCounted = false))
        assertTrue("step_counted 는 0 이어야 한다: $row", row.endsWith(",0"))
    }

    @Test
    fun decimal_separator_is_dot_regardless_of_default_locale() {
        // 콤마 소수점을 쓰는 로케일(예: 독일)에서도 CSV 가 깨지지 않도록 점(.)으로 고정한다.
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = WaveformCsv.row(WaveformLabel.NORMAL_WALK, sample(x = 1.5f))
            assertTrue("소수점은 점이어야 한다: $row", row.contains("1.50000"))
            assertFalse("콤마 소수점이 섞이면 안 된다: $row", row.contains("1,50000"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun of_empty_samples_is_header_only() {
        assertEquals(WaveformCsv.HEADER, WaveformCsv.of(WaveformLabel.NORMAL_WALK, emptyList()))
    }

    @Test
    fun of_prepends_header_then_one_line_per_sample() {
        val csv = WaveformCsv.of(
            WaveformLabel.NORMAL_WALK,
            listOf(sample(elapsedMs = 0L), sample(elapsedMs = 20L)),
        )
        val lines = csv.split("\n")
        assertEquals(3, lines.size) // 헤더 + 2행
        assertEquals(WaveformCsv.HEADER, lines[0])
        assertTrue(lines[1].startsWith("normal_walk,0,"))
        assertTrue(lines[2].startsWith("normal_walk,20,"))
    }

    // ── 레코더 상태 ──────────────────────────────────────────

    @Test
    fun start_sets_label_clears_buffer_and_records() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK)
        r.add(sample(elapsedMs = 1L))
        r.start(WaveformLabel.WALK_THEN_SIT) // 재시작 → 이전 버퍼 비움 + 라벨 교체
        assertEquals(WaveformLabel.WALK_THEN_SIT, r.label)
        assertTrue(r.isRecording)
        assertEquals(0, r.sampleCount)
    }

    @Test
    fun add_appends_only_while_recording() {
        val r = WaveformRecorder()
        r.add(sample()) // 시작 전 → 무시
        assertEquals(0, r.sampleCount)
        r.start(WaveformLabel.NORMAL_WALK)
        r.add(sample())
        r.add(sample())
        assertEquals(2, r.sampleCount)
        r.stop()
        r.add(sample()) // 정지 후 → 무시
        assertEquals(2, r.sampleCount)
    }

    @Test
    fun stop_keeps_buffer_for_export() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.WALK_THEN_SIT)
        r.add(sample(elapsedMs = 5L))
        r.stop()
        assertFalse(r.isRecording)
        assertEquals(1, r.sampleCount) // 정지해도 내보내기용으로 유지
        assertTrue(r.toCsv().contains("walk_then_sit,5,"))
    }

    @Test
    fun reset_clears_buffer_and_recording() {
        val r = WaveformRecorder()
        r.start(WaveformLabel.NORMAL_WALK)
        r.add(sample())
        r.reset()
        assertFalse(r.isRecording)
        assertEquals(0, r.sampleCount)
        assertEquals(WaveformCsv.HEADER, r.toCsv()) // 비면 헤더만
    }
}
