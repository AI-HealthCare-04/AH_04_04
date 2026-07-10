package com.aihealthcare.ah0404.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 걷기 흐름 요청 DTO들이 "실제로 전송되는 JSON"을 검증한다(백엔드/에뮬레이터 불필요).
 *
 * NetworkClient 의 Json 과 동일 설정(ignoreUnknownKeys=true, encodeDefaults 기본=false)을
 * 그대로 재현해 직렬화 결과를 확인한다. 가장 중요한 계약:
 *   - PATCH 완료 body 에 "success": true 가 반드시 포함되어야 한다.
 */
class RequestSerializationTest {

    // NetworkClient 와 동일 설정 (private val json = Json { ignoreUnknownKeys = true })
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missionLogCreate_serialization() {
        val body = MissionLogCreateRequest(
            missionTemplateId = 1,
            missionType = "walking",
            status = "in_progress",
        )
        val s = json.encodeToString(body)
        println("POST /mission-logs  →  $s")
        assertTrue(s.contains("\"mission_type\":\"walking\""))
        assertTrue(s.contains("\"status\":\"in_progress\""))
    }

    @Test
    fun sensorSession_serialization() {
        val body = SensorSessionCreateRequest(
            missionLogId = 42,
            sensorType = "accelerometer",
            recognitionStatus = "success",
            detectedCount = 1000,
            durationSec = 600,
        )
        val s = json.encodeToString(body)
        println("POST /sensor-sessions  →  $s")
        assertTrue(s.contains("\"sensor_type\":\"accelerometer\""))
        assertTrue(s.contains("\"recognition_status\":\"success\""))
    }

    @Test
    fun missionLogUpdate_containsSuccessTrue() {
        val body = MissionLogUpdateRequest(
            success = true,
            status = "completed",
            walkingDetail = WalkingDetail(
                durationMin = 10f,
                distanceKm = 0.7f,
                steps = 1000,
            ),
        )
        val s = json.encodeToString(body)
        println("PATCH /mission-logs/{id}  →  $s")

        // ⚠️ 가장 중요: success=true 가 실제 body 에 실려야 한다.
        assertTrue("PATCH body 에 \"success\":true 가 있어야 한다", s.contains("\"success\":true"))
        // status="completed" 도 (기본값이지만) 실려야 한다
        assertTrue("PATCH body 에 status 가 있어야 한다", s.contains("\"status\":\"completed\""))
        // walking_detail.duration_min 필수
        assertTrue(s.contains("\"duration_min\":10"))
    }
}
