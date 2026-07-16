package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 운동 영상(_운동하기 4단계) DTO — dev 백엔드 정본(#72 GET /exercise-videos) 기준.
 *
 *  불변식: available == (video_url != null). available=false 면 앱은 "준비중" 표시(탭은 유지).
 *  stage 키(warmup/seated/standing/cooldown)는 안정적 식별자 — 번들 폴백(res/raw) 매핑 키로도 쓴다.
 *  video_url 은 EC2 nginx 스트리밍(HTTPS), thumbnail_url 은 현재 전부 null.
 */
@Serializable
data class ExerciseVideoItem(
    val stage: String,     // warmup | seated | standing | cooldown
    val label: String,     // 화면 표시 라벨(예: "서서 운동")
    val order: Int,        // 탭 정렬 순서(1부터)
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val available: Boolean = false,
)

@Serializable
data class ExerciseVideosResponse(
    val videos: List<ExerciseVideoItem> = emptyList(),
)
