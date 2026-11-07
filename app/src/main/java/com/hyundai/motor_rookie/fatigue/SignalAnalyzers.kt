package com.hyundai.motor_rookie.fatigue

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** 최근 N개 값만 들고 있는 창. 평균·표준편차 계산용. */
class RollingWindow(private val size: Int) {
    private val values = ArrayDeque<Double>()

    fun add(v: Double) {
        values.addLast(v)
        if (values.size > size) values.removeFirst()
    }

    val isFull: Boolean get() = values.size >= size
    fun clear() = values.clear()

    fun mean(): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun std(): Double {
        if (values.size < 2) return 0.0
        val m = mean()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    fun min(): Double = values.minOrNull() ?: 0.0

    /** 최근 [lastN]초 동안 1초당 평균 변화량 (감속 중이면 음수) */
    fun trend(lastN: Int = 10): Double {
        val l = values.toList().takeLast(lastN + 1)
        return if (l.size < 2) 0.0 else (l.last() - l.first()) / (l.size - 1)
    }

    /**
     * 1초 간 변화량의 표준편차 = "출렁임".
     * 그냥 표준편차를 쓰면 가속·감속만 해도 커지기 때문에, 변화량으로 본다.
     */
    fun diffStd(): Double {
        val l = values.toList()
        if (l.size < 3) return 0.0
        val d = (1 until l.size).map { l[it] - l[it - 1] }
        val m = d.average()
        return sqrt(d.sumOf { (it - m) * (it - m) } / (d.size - 1))
    }
}

/**
 * 핸들 각도 패턴 분석 (1초에 1개씩 넣는다고 가정).
 *
 * - 미세 보정: 핸들을 살짝 왼쪽→오른쪽처럼 "방향을 바꿔" 조금 움직인 횟수.
 *   깨어 있을 때는 계속 조금씩 보정하고, 졸리면 보정이 줄어든다.
 * - 급조작: 한 번에 크게 꺾은 횟수. 졸다가 깜짝 놀라 바로잡을 때 생긴다.
 */
class SteeringAnalyzer(private val windowSec: Int) {
    private val angles = ArrayDeque<Double>()

    fun add(deg: Double) {
        angles.addLast(deg)
        if (angles.size > windowSec + 1) angles.removeFirst()
    }

    val isFull: Boolean get() = angles.size > windowSec
    fun clear() = angles.clear()

    /** 분당 미세 보정 횟수 */
    fun microCorrectionsPerMin(): Double {
        if (angles.size < 3) return 0.0
        var count = 0
        var prevDelta = 0.0
        val list = angles.toList()
        for (i in 1 until list.size) {
            val d = list[i] - list[i - 1]
            if (abs(d) in MICRO_MIN..MICRO_MAX && prevDelta != 0.0 && sign(d) != sign(prevDelta)) count++
            if (abs(d) >= NOISE) prevDelta = d
        }
        return count * 60.0 / (list.size - 1)
    }

    /** 창 안의 급조작 횟수 */
    fun jerkCount(): Int {
        val list = angles.toList()
        return (1 until list.size).count { abs(list[it] - list[it - 1]) >= JERK }
    }

    /** 평균 핸들 각도 크기 (직진 여부 판단) */
    fun meanAbs(): Double = if (angles.isEmpty()) 0.0 else angles.sumOf { abs(it) } / angles.size

    companion object {
        const val NOISE = 0.05  // 이보다 작은 변화는 무시
        const val MICRO_MIN = 0.3
        const val MICRO_MAX = 4.0
        const val JERK = 6.0    // 1초에 6도 이상 = 급조작
    }
}

/**
 * 운전자 패턴 학습 (간단 버전, 기획서 4.2-③).
 * 처음 15분 동안의 "평소" 미세 보정 횟수와 속도 흔들림을 기억해 기준선으로 쓴다.
 */
class Baseline(private val config: FatigueConfig) {
    var microRate: Double = config.defaultMicroRate; private set
    var speedStd: Double = config.defaultSpeedStd; private set
    var ready: Boolean = false; private set

    private val rates = mutableListOf<Double>()
    private val stds = mutableListOf<Double>()

    fun offer(drivingSec: Double, rate: Double, std: Double) {
        if (ready) return
        rates += rate
        stds += std
        if (drivingSec >= config.baselineSec && rates.size >= 60) {
            microRate = rates.median().coerceAtLeast(3.0)
            speedStd = stds.median().coerceAtLeast(0.8)
            ready = true
        }
    }

    private fun List<Double>.median(): Double = sorted().let { it[it.size / 2] }
}
