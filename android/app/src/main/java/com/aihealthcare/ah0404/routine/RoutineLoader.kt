package com.aihealthcare.ah0404.routine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * assets/routines/{fileName} 의 JSON을 Routine으로 파싱한다.
 * kotlinx.serialization 대신 안드로이드 내장 org.json 사용(추가 의존성 없음).
 *
 * 검증 정책(팀원이 새 루틴 JSON을 추가할 때 실수를 조기에 잡기 위함):
 *  - 구조 오류(sec<=0, count 모드인데 count 없음, video/image인데 asset 없음, steps 비어있음)
 *    → 모아서 예외(잘못된 루틴을 조용히 재생하지 않고 개발 단계에서 바로 드러냄).
 *  - 자원 누락(res/raw 영상·BGM, assets/exercise 이미지) → 경고 로그만.
 *    플레이어가 플레이스홀더/무음으로 우아하게 대체하므로 로드는 계속한다.
 */
object RoutineLoader {

    private const val TAG = "RoutineLoader"

    fun load(context: Context, fileName: String): Routine {
        val text = try {
            context.assets.open("routines/$fileName").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalArgumentException("루틴 파일을 열 수 없습니다: routines/$fileName", e)
        }
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("루틴 JSON 형식 오류: $fileName", e)
        }

        val stepsArr = obj.getJSONArray("steps")
        val steps = (0 until stepsArr.length()).map { i ->
            val s = stepsArr.getJSONObject(i)
            Step(
                type = parseEnum<StepType>(s.getString("type"), "type", fileName, i),
                sec = s.getInt("sec"),
                name = s.getString("name"),
                guide = s.optString("guide", ""),
                asset = s.optString("asset", "").ifEmpty { null },
                mode = s.optString("mode", "").ifEmpty { null }
                    ?.let { parseEnum<StepMode>(it, "mode", fileName, i) } ?: StepMode.NONE,
                count = if (s.has("count")) s.getInt("count") else null,
                mirror = s.optBoolean("mirror", false),
                safety = s.optString("safety", "").ifEmpty { null },
            )
        }

        val routine = Routine(
            id = obj.getString("id"),
            title = obj.getString("title"),
            subtitle = obj.optString("subtitle", ""),
            bgm = obj.getString("bgm"),
            totalSec = obj.getInt("totalSec"),
            steps = steps,
        )

        val errors = validateStructure(routine).toMutableList()
        // VIDEO 자원 누락은 오류(로드 차단) — 이유는 missingVideoResources 주석 참고(안전).
        errors += missingVideoResources(routine) { rawExists(context, it) }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("루틴 검증 실패 ($fileName):\n- " + errors.joinToString("\n- "))
        }
        warnMissingSoftAssets(context, routine, fileName)
        return routine
    }

    /**
     * Context 없이 검증 가능한 구조 규칙. 오류 메시지 목록을 반환(빈 리스트면 정상).
     * 유닛 테스트 대상.
     */
    internal fun validateStructure(routine: Routine): List<String> {
        val errors = mutableListOf<String>()

        if (routine.steps.isEmpty()) errors += "steps가 비어 있습니다"

        routine.steps.forEachIndexed { i, s ->
            val where = "steps[$i]('${s.name}')"
            if (s.sec <= 0) {
                errors += "$where sec는 0보다 커야 합니다 (현재 ${s.sec})"
            }
            if (s.mode == StepMode.COUNT && (s.count == null || s.count <= 0)) {
                errors += "$where mode=count 인데 count가 없거나 0 이하입니다 (현재 ${s.count})"
            }
            if ((s.type == StepType.VIDEO || s.type == StepType.IMAGE) && s.asset == null) {
                errors += "$where type=${s.type.name.lowercase()} 인데 asset이 없습니다"
            }
        }
        return errors
    }

    /**
     * VIDEO 단계의 res/raw 영상 존재를 검증한다. 누락 시 '오류'(로드 차단) — 경고가 아니다.
     *
     * 왜 오류인가(지영 리뷰 #82): 플레이어는 영상 자원이 없으면 클립 교체를 건너뛰어
     * 직전 VIDEO 단계의 영상이 계속 반복 재생될 수 있다. 그러면 새 단계의 동작명·안내와
     * 다른 운동 영상이 표시되어 어르신이 잘못된 동작을 따라 하게 된다(안전 문제). 따라서
     * 이미지/BGM 누락(우아한 대체 가능)과 달리 영상 누락은 아예 로드를 막는다.
     *
     * videoExists(자원 존재 여부)를 주입받아 Context 없이 유닛 테스트 가능하다.
     * (asset==null 인 VIDEO는 validateStructure가 이미 잡으므로 여기선 이름만 있고 파일이 없는 경우.)
     */
    internal fun missingVideoResources(routine: Routine, videoExists: (String) -> Boolean): List<String> {
        val errors = mutableListOf<String>()
        routine.steps.forEachIndexed { i, s ->
            if (s.type == StepType.VIDEO && s.asset != null && !videoExists(s.asset)) {
                errors += "steps[$i]('${s.name}') 영상 res/raw/${s.asset} 이(가) 없습니다 " +
                    "(영상 누락 시 직전 동작 영상이 계속 재생될 수 있어 로드를 막습니다)"
            }
        }
        return errors
    }

    /** 비치명 자원 누락만 경고 — 플레이어가 실제로 우아하게 대체하는 것들(BGM 무음, 이미지 플레이스홀더). */
    private fun warnMissingSoftAssets(context: Context, routine: Routine, file: String) {
        if (!rawExists(context, routine.bgm)) {
            Log.w(TAG, "$file: BGM res/raw/${routine.bgm} 없음 (무음으로 진행)")
        }
        routine.steps.forEachIndexed { i, s ->
            if (s.type == StepType.IMAGE && s.asset != null && !assetImageExists(context, s.asset)) {
                Log.w(TAG, "$file steps[$i]: 이미지 assets/exercise/${s.asset}.jpg 없음 (플레이스홀더 표시)")
            }
        }
        val sum = routine.steps.sumOf { it.sec }
        if (routine.totalSec != sum) {
            Log.w(TAG, "$file: totalSec(${routine.totalSec}) != 각 단계 합($sum) — 표시용 값 갱신 권장")
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String, field: String, file: String, idx: Int): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "$file steps[$idx].$field 값이 올바르지 않습니다: '$raw' " +
                    "(가능: ${enumValues<T>().joinToString { it.name.lowercase() }})",
            )

    // JSON 루틴의 동적 자산 이름으로 조회해야 해서 getIdentifier가 맞는 방법(플레이어 rawUri와 동일, 의도적).
    @SuppressLint("DiscouragedApi")
    private fun rawExists(context: Context, name: String): Boolean =
        context.resources.getIdentifier(name, "raw", context.packageName) != 0

    private fun assetImageExists(context: Context, name: String): Boolean =
        runCatching { context.assets.open("exercise/$name.jpg").close() }.isSuccess
}
