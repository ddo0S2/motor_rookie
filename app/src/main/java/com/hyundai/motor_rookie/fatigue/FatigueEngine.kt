package com.hyundai.motor_rookie.fatigue

import com.hyundai.motor_rookie.vehicle.Gear
import com.hyundai.motor_rookie.vehicle.VehicleSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 피로 점수 계산기 (1번 담당의 핵심).
 *
 *   F = 100 × min(1, m × (0.25·s_time + 0.30·s_steer + 0.15·s_speed + 0.30·s_talk + 0.15·s_signal))
 *
 * 사용법:
 *   engine.onSample(sample)                 // 차량 데이터가 올 때마다 (약 1초마다)
 *   engine.onTalkResult(answered, correct, ms) // 2번 담당: 퀴즈 결과가 나올 때마다
 *   engine.onRestTaken()                    // 3번 담당: 파워냅이 끝났을 때
 *   engine.state.collect { ... }            // 화면·개입 쪽에서 구독
 *
 * 안드로이드 코드에 의존하지 않아서 JVM 단위 테스트로 바로 검증할 수 있다.
 */
class FatigueEngine(private val config: FatigueConfig = FatigueConfig()) {

    private val _state = MutableStateFlow(FatigueState())
    val state: StateFlow<FatigueState> = _state.asStateFlow()

    /** 설정 화면의 개입 민감도 */
    var sensitivity: Sensitivity = Sensitivity.NORMAL

    private val steering = SteeringAnalyzer(config.windowSec)
    private val speedWin = RollingWindow(config.windowSec)
    private val slowSpeedWin = RollingWindow(config.congestionWindowSec)
    private val baseline = Baseline(config)
    private val warningTimes = ArrayDeque<Long>()

    private var lastMs: Long? = null
    private var driveStartMs: Long? = null
    private var parkedSinceMs: Long? = null
    private var sTalk = 0.0
    private var sSteer = 0.0
    private var sSpeed = 0.0
    private var smoothed = 0.0
    private var level = FatigueLevel.NORMAL
    private var noResponseCount = 0

    /** 차량 데이터 1개 처리 → 새 상태를 돌려주고 [state]에도 반영 */
    fun onSample(s: VehicleSample): FatigueState {
        val dtSec = lastMs?.let { (s.timeMs - it).coerceAtLeast(0) / 1000.0 } ?: 1.0
        lastMs = s.timeMs

        // 대화 점수는 시간이 지나면 자연히 줄어든다
        sTalk *= exp(-dtSec / config.talkDecaySec)

        // ── 휴식 감지: P단 5분 이상 ──
        val parked = s.gear == Gear.PARK
        if (parked) {
            if (parkedSinceMs == null) parkedSinceMs = s.timeMs
            if (s.timeMs - parkedSinceMs!! >= config.restMinSec * 1000L) resetForRest()
        } else {
            parkedSinceMs = null
            if (driveStartMs == null) driveStartMs = s.timeMs
        }

        // ── s_time ──
        val drivingSec = driveStartMs?.let { (s.timeMs - it) / 1000.0 } ?: 0.0
        val sTime = (drivingSec / config.fullFatigueSec).coerceIn(0.0, 1.0)

        // ── s_steer, s_speed ──
        // 고속도로에서 일정한 속도로 달릴 때만 새로 계산한다.
        // (시내·감속·차선 변경 구간은 핸들이 원래 많이 움직여서 졸음과 구분이 안 됨)
        // 그 외 구간에서는 직전 값이 천천히 줄어든다.
        steering.add(s.steeringDeg.toDouble())
        speedWin.add(s.speedKmh.toDouble())
        slowSpeedWin.add(s.speedKmh.toDouble())
        val cruising = !parked && steering.isFull && speedWin.isFull &&
            speedWin.min() >= config.highwaySpeedKmh &&
            abs(speedWin.trend()) < config.steadyTrendKmhPerSec
        if (!cruising) {
            val k = exp(-dtSec / config.leaveDecaySec)
            sSteer *= k
            sSpeed *= k
        } else {
            val rate = steering.microCorrectionsPerMin()
            val std = speedWin.diffStd()
            baseline.offer(drivingSec, rate, std)
            if (baseline.ready) {
                val drop = (1 - rate / baseline.microRate).coerceAtLeast(0.0)
                val microPart = ((drop - config.microDropStart) / (config.microDropFull - config.microDropStart))
                    .coerceIn(0.0, 1.0)
                val jerkPart = (steering.jerkCount() / config.jerksFull).coerceIn(0.0, 1.0)
                sSteer = 0.6 * microPart + 0.4 * jerkPart
                val ratio = std / baseline.speedStd
                sSpeed = ((ratio - 1) / (config.speedStdRatioFull - 1)).coerceIn(0.0, 1.0)
            }
        }

        // ── s_signal: 10분 안의 순정 경고 횟수 ──
        if (s.warning != null) warningTimes.addLast(s.timeMs)
        while (warningTimes.isNotEmpty() && s.timeMs - warningTimes.first() > config.signalWindowSec * 1000L) {
            warningTimes.removeFirst()
        }
        val sSignal = when (warningTimes.size) { 0 -> 0.0; 1 -> 0.5; else -> 1.0 }

        // ── 상황 가중치 m ──
        val reasons = mutableListOf<String>()
        var m = 1.0
        if (s.hourOfDay >= 22 || s.hourOfDay < 6) { m *= config.nightFactor; reasons += "야간" }
        if (s.wiperOn) { m *= config.wiperFactor; reasons += "비·눈" }
        if (s.gear == Gear.DRIVE && slowSpeedWin.isFull && slowSpeedWin.mean() in 1.0..config.congestionSpeedKmh) {
            m *= config.congestionFactor; reasons += "정체"
        }
        if (s.speedKmh >= 80f && steering.isFull && steering.meanAbs() < 3.0) {
            m *= config.straightFactor; reasons += "고속 직진"
        }

        // ── 점수 ──
        val raw = config.wTime * sTime + config.wSteer * sSteer + config.wSpeed * sSpeed +
            config.wTalk * sTalk + config.wSignalBonus * sSignal
        val target = 100 * min(1.0, m * raw)
        val alpha = 1 - exp(-dtSec / config.smoothingSec)
        smoothed += alpha * (target - smoothed)
        val score = smoothed.roundToInt().coerceIn(0, 100)
        level = nextLevel(score)

        val newState = FatigueState(
            score = score,
            level = level,
            signals = FatigueSignals(sTime, sSteer, sSpeed, sTalk, sSignal),
            multiplier = m,
            reasons = reasons,
            drivingMinutes = (drivingSec / 60).toInt(),
            parked = parked,
            baselineReady = baseline.ready,
            noResponseCount = noResponseCount,
        )
        _state.value = newState
        return newState
    }

    /**
     * 2번 담당(대화)이 퀴즈 한 문제가 끝날 때마다 호출.
     * @param answered   대답을 했는지 (무응답이면 false)
     * @param correct    맞혔는지
     * @param responseMs 질문이 끝나고 대답하기까지 걸린 시간
     */
    fun onTalkResult(answered: Boolean, correct: Boolean, responseMs: Long) {
        sTalk += when {
            !answered -> 0.4
            !correct -> 0.2
            responseMs > 3000 -> 0.15
            else -> -0.15
        }
        sTalk = sTalk.coerceIn(0.0, 1.0)
        noResponseCount = if (answered) 0 else noResponseCount + 1
        _state.value = _state.value.copy(
            signals = _state.value.signals.copy(talk = sTalk),
            noResponseCount = noResponseCount,
        )
    }

    /** 3번 담당(파워냅)이 휴식을 마쳤을 때 호출. P단 5분 자동 감지와 같은 효과. */
    fun onRestTaken() = resetForRest()

    private fun resetForRest() {
        driveStartMs = null
        sTalk = 0.0
        sSteer = 0.0
        sSpeed = 0.0
        smoothed = 0.0
        noResponseCount = 0
        warningTimes.clear()
        steering.clear()
        speedWin.clear()
        level = FatigueLevel.NORMAL
    }

    /** 올라갈 때는 기준 점수에서 바로, 내려갈 때는 기준보다 5점 더 낮아져야 내려간다. */
    private fun nextLevel(score: Int): FatigueLevel {
        val up = levelFor(score, 0)
        if (up.ordinal >= level.ordinal) return up
        val down = levelFor(score, config.hysteresis)
        return if (down.ordinal <= level.ordinal) down else level
    }

    private fun levelFor(score: Int, margin: Int): FatigueLevel {
        val t = sensitivity
        return when {
            score >= t.rest - margin -> FatigueLevel.REST
            score >= t.environment - margin -> FatigueLevel.ENVIRONMENT
            score >= t.talk - margin -> FatigueLevel.TALK
            else -> FatigueLevel.NORMAL
        }
    }
}
