package com.aihealthcare.ah0404.routine

import android.content.Context
import org.json.JSONObject

/**
 * assets/routines/{fileName} 의 JSON을 Routine으로 파싱한다.
 * kotlinx.serialization 대신 안드로이드 내장 org.json 사용(추가 의존성 없음).
 */
object RoutineLoader {

    fun load(context: Context, fileName: String): Routine {
        val text = context.assets.open("routines/$fileName")
            .bufferedReader().use { it.readText() }
        val obj = JSONObject(text)

        val stepsArr = obj.getJSONArray("steps")
        val steps = (0 until stepsArr.length()).map { i ->
            val s = stepsArr.getJSONObject(i)
            Step(
                type = StepType.valueOf(s.getString("type").uppercase()),
                sec = s.getInt("sec"),
                name = s.getString("name"),
                guide = s.optString("guide", ""),
                asset = s.optString("asset", "").ifEmpty { null },
                mode = s.optString("mode", "").ifEmpty { null }
                    ?.let { StepMode.valueOf(it.uppercase()) } ?: StepMode.NONE,
                count = if (s.has("count")) s.getInt("count") else null,
                mirror = s.optBoolean("mirror", false),
                safety = s.optString("safety", "").ifEmpty { null },
            )
        }

        return Routine(
            id = obj.getString("id"),
            title = obj.getString("title"),
            subtitle = obj.optString("subtitle", ""),
            bgm = obj.getString("bgm"),
            totalSec = obj.getInt("totalSec"),
            steps = steps,
        )
    }
}
