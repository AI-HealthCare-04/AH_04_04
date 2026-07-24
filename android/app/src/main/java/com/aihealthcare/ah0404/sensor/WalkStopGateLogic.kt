package com.aihealthcare.ah0404.sensor

import kotlin.math.sqrt

/**
 * #131: '보행 직후 곧바로 앉기' 과다카운트 억제용 **적응형 정지판정 게이트** (순수 로직, 센서 없이 테스트 가능).
 *
 * 배경(#89 §5-5 / WalkingStepDetectorLogic 알려진 한계): '정상 보행 직후 앉기'는 앉기 피크 간격이
 * ≈882ms(68보/분)로 정상 보행 대역(500~1000ms) 한가운데라, **피크 간격이라는 단일 신호로는 분리 불가**다.
 * 그래서 간격 게이트만 쓰는 감지기는 앉는 동안에도 걸음을 계속 센다(과다카운트).
 *
 * 이 게이트는 간격이 아니라 **에너지**를 본다: phase 라벨 없이 벽시계 시간 구간만으로 전체 스트림을
 * 슬라이딩 윈도우로 훑어, **각 순간의 '자기 보행 기준선(baseline) 대비 말미 에너지 비'** 로 '보행→정지'
 * 전환을 판정한다. 에너지는 스칼라 magnitude 의 std 가 아니라 **3축 동적 벡터 RMS**(윈도우 국소 중력
 * 제거 후 |동적|의 RMS)로 잰다 — 중력이 지배해 크기가 거의 안 변하는 함정(#131)을 피하고 수평 진동까지 잡는다.
 *
 * 판정(파형 분석 findings §4 의 채택안 = 적응형 r2, docs/sensor_waveform_pilot_findings.md):
 *   - baseline B = 말미 직전 [now-TAIL-BASE_SPAN, now-TAIL] 구간 윈도우 동적RMS 의 중앙값
 *     (= '그 세션 자신의 보행 수준'. 고정 절대문턱이 아니라 세션마다 적응).
 *   - tail   E = 마지막 TAIL(2초) 윈도우 동적RMS 의 중앙값 (**2초** — 앉는 순간 임팩트 스파이크를 피하고
 *                완전히 정착한 구간만 본다. 4초 창은 스파이크를 물어 정지 포착이 낮았다: findings §4).
 *   - 정지 = **B ≥ WALK_REF_MIN_DYN**(애초에 보행 중이었고) 이고 **E < STOP_RATIO·B**(말미가 기준선의 40% 미만).
 *
 * 실측 검증(pilot_findings §4, 품질통과 20 trial): 정상보행 정지오판 0/6(진짜 걸음 안 끊음=과소계수 위험 0),
 * walk_then_sit 정지 포착 4/4. n=20·참여자 2 라 **설계 방향**이며 파라미터(k·창)는 본 수집(120 trial)에서 확정한다.
 *
 * 감지기 연결: WalkingStepDetectorLogic 이 매 샘플을 이 게이트에 흘려보내고, isStopped=true 동안 걸음
 * 카운트를 **동결**한다(재보행이 확정될 때까지). #89 피크/간격 로직 자체는 건드리지 않는다.
 */
class WalkStopGateLogic {

    companion object {
        const val DEFAULT_WIN_MS = 1000L           // 슬라이딩 윈도우 길이
        const val DEFAULT_HOP_MS = 500L            // 윈도우 이동 간격
        const val DEFAULT_TAIL_MS = 2000L          // 말미(정착) 구간 = r2. 4초가 아님(임팩트 스파이크 회피)
        const val DEFAULT_BASE_SPAN_MS = 5000L     // 기준선 구간 폭(말미 직전 구간)
        const val DEFAULT_WALK_REF_MIN_DYN = 1.0f  // 기준선이 이 미만이면 '애초에 보행 아님'(정지판정 안 함)
        const val DEFAULT_STOP_RATIO = 0.4f        // 말미 < k×기준선 이면 정지(k)
        const val MIN_WINDOWS_BASE = 3             // 기준선 최소 윈도우 수(부족하면 웜업 → 정지판정 보류)
        const val MIN_WINDOWS_TAIL = 2             // 말미 최소 윈도우 수
    }

    var winMs = DEFAULT_WIN_MS
    var hopMs = DEFAULT_HOP_MS
    var tailMs = DEFAULT_TAIL_MS
    var baseSpanMs = DEFAULT_BASE_SPAN_MS
    var walkRefMinDyn = DEFAULT_WALK_REF_MIN_DYN
    var stopRatio = DEFAULT_STOP_RATIO

    /** '보행 중이었는데 말미가 잦아들어 정지로 전환'했으면 true. 웜업/보행중엔 false. */
    var isStopped = false
        private set

    /** 디버그: 마지막 판정에 쓴 기준선/말미 동적RMS (없으면 NaN). */
    var baseline = Float.NaN
        private set
    var tailEnergy = Float.NaN
        private set

    // 최근 win 구간 샘플 버퍼(윈도우 RMS 계산용)
    private val sT = ArrayDeque<Long>()
    private val sX = ArrayDeque<Float>()
    private val sY = ArrayDeque<Float>()
    private val sZ = ArrayDeque<Float>()

    // 윈도우 동적RMS 트레이스(윈도우 종료시각, RMS)
    private val wEnd = ArrayDeque<Long>()
    private val wRms = ArrayDeque<Float>()

    private var nextHopAtMs = Long.MIN_VALUE

    /** 가속도 샘플 1개를 흘려보낸다(원시 3축). 순서는 시간 오름차순 가정. */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (nextHopAtMs == Long.MIN_VALUE) nextHopAtMs = timestampMs + hopMs

        sT.addLast(timestampMs); sX.addLast(x); sY.addLast(y); sZ.addLast(z)

        // hop 경계마다 최근 win 윈도우의 동적RMS 를 트레이스에 추가(트림 전에 계산 — 윈도우가 필요한
        // 과거 샘플이 아직 버퍼에 있어야 한다).
        while (timestampMs >= nextHopAtMs) {
            val e = windowDynRms(nextHopAtMs)
            if (!e.isNaN()) {
                wEnd.addLast(nextHopAtMs); wRms.addLast(e)
            }
            nextHopAtMs += hopMs
        }
        // 남은 pending 윈도우는 timestampMs-winMs 이후 샘플만 필요 → 그보다 오래된 샘플은 정리.
        while (sT.isNotEmpty() && timestampMs - sT.first() > winMs) {
            sT.removeFirst(); sX.removeFirst(); sY.removeFirst(); sZ.removeFirst()
        }
        // 트레이스는 baseline+tail 판정에 필요한 horizon 만 유지.
        val horizon = tailMs + baseSpanMs + winMs
        while (wEnd.isNotEmpty() && timestampMs - wEnd.first() > horizon) {
            wEnd.removeFirst(); wRms.removeFirst()
        }
        updateStopFlag(timestampMs)
    }

    /** windowEndMs 를 끝으로 하는 trailing win 윈도우의 3축 동적 벡터 RMS. 표본 부족이면 NaN. */
    private fun windowDynRms(windowEndMs: Long): Float {
        var n = 0
        var mx = 0f; var my = 0f; var mz = 0f
        val idx = ArrayList<Int>()
        for (i in sT.indices) {
            val t = sT.elementAt(i)
            if (t > windowEndMs - winMs && t <= windowEndMs) {
                idx.add(i); mx += sX.elementAt(i); my += sY.elementAt(i); mz += sZ.elementAt(i); n++
            }
        }
        if (n < 4) return Float.NaN
        mx /= n; my /= n; mz /= n                       // 윈도우 국소 중력 추정 = 평균 벡터
        var sumSq = 0f
        for (i in idx) {
            val dx = sX.elementAt(i) - mx
            val dy = sY.elementAt(i) - my
            val dz = sZ.elementAt(i) - mz
            sumSq += dx * dx + dy * dy + dz * dz          // |동적|^2
        }
        return sqrt(sumSq / n)
    }

    private fun updateStopFlag(nowMs: Long) {
        val tail = ArrayList<Float>()
        val base = ArrayList<Float>()
        val tailStart = nowMs - tailMs
        val baseStart = nowMs - tailMs - baseSpanMs
        for (i in wEnd.indices) {
            val te = wEnd.elementAt(i)
            val e = wRms.elementAt(i)
            when {
                te > tailStart -> tail.add(e)
                te > baseStart -> base.add(e)   // (baseStart, tailStart]
            }
        }
        if (tail.size < MIN_WINDOWS_TAIL || base.size < MIN_WINDOWS_BASE) {
            baseline = Float.NaN; tailEnergy = Float.NaN; isStopped = false
            return
        }
        val b = median(base)
        val e = median(tail)
        baseline = b; tailEnergy = e
        isStopped = b >= walkRefMinDyn && e < stopRatio * b
    }

    private fun median(v: List<Float>): Float {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    fun reset() {
        isStopped = false
        baseline = Float.NaN
        tailEnergy = Float.NaN
        sT.clear(); sX.clear(); sY.clear(); sZ.clear()
        wEnd.clear(); wRms.clear()
        nextHopAtMs = Long.MIN_VALUE
    }
}
