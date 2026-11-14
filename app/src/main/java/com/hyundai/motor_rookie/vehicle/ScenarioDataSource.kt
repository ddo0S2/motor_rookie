package com.hyundai.motor_rookie.vehicle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * 시연·테스트용 "조작형" 주행 데이터.
 *
 * CSV 는 정해진 순서대로 혼자 흘러가지만, 이 소스는 시뮬레이션 패널에서 고른 운전 상태대로
 * 핸들·속도 값을 그때그때 만들어 낸다. 그래서 단계마다 멈춰서 확인할 수 있다.
 *
 *   0 정상      : 핸들을 2초마다 반대로 살짝 보정, 속도 일정          → 괜찮음 유지 (0~39)
 *   1 조금 졸림  : 보정이 6초에 한 번으로 줄고, 1분에 1번 급조작, 속도 출렁 → 대화 단계 (40~59)
 *   2 많이 졸림  : 보정이 8초에 한 번, 1분에 2번 급조작, 속도 크게 출렁   → 환경 단계 (60~74)
 *   3 아주 졸림  : 2번 + 3분마다 차선 이탈 경고(LDW)                  → 휴식 단계 (75~)
 *   (밤 23시 기준. 점수 범위는 감지 엔진을 PC 에서 그대로 돌려 맞춘 값)
 *
 * - 기어는 에뮬레이터 Virtual Console 의 실제 기어를 따른다 (parkedFlow).
 *   P 에서는 정차 데이터(0km/h)만 나가므로 운전이 진행되지 않는다. D 로 바꾸면 주행 시작.
 * - 고른 상태는 다른 버튼을 누를 때까지 유지된다. 퀴즈 중에도 시간은 흐른다
 *   (곁은 단계가 유지되는 동안 쿨다운마다 퀴즈를 다시 내기 때문에, 퀴즈 중에 시간을 멈추면 다음 단계로 못 간다).
 * - 실제 운행(LIVE)에서는 이 소스를 쓰지 않는다. 그때는 운전자의 실제 핸들·속도·기어로 점수가 바뀐다.
 * - 처음 몇 분은 감지 엔진이 '평소 운전'을 배우는 시간이라, 그동안은 정상(0)으로 두는 게 좋다.
 *
 * @param speedFactor 1초에 몇 초 분량을 흘려보낼지. 5 = 5배속 (기준선 3분 → 실제 36초)
 * @param parkedFlow  에뮬레이터 실제 기어가 P 인지 (GearMonitor). null 이면 항상 D 로 본다.
 */
class ScenarioDataSource(
    private val scope: CoroutineScope,
    private val speedFactor: Double = 5.0,
    private val startHour: Int = 23,
    private val parkedFlow: Flow<Boolean>? = null,
    seed: Int = 3,
) : VehicleDataSource {

    private val _samples = MutableSharedFlow<VehicleSample>(replay = 1, extraBufferCapacity = 64)
    override val samples: SharedFlow<VehicleSample> = _samples.asSharedFlow()

    private val _level = MutableStateFlow(0)
    /** 지금 운전 상태 0~3 (시뮬레이션 패널이 바꾼다) */
    val level: StateFlow<Int> = _level.asStateFlow()
    fun setLevel(value: Int) { _level.value = value.coerceIn(0, 3) }

    /** true 면 시간을 멈춘다 (퀴즈 중) */
    @Volatile var paused: Boolean = false

    @Volatile private var parked = parkedFlow != null   // 실제 기어를 알기 전에는 P 로 본다
    private var job: Job? = null
    private var gearJob: Job? = null

    // 생성기 상태
    private val rnd = Random(seed)
    private var tSec = 0L          // 흘러간 '주행 시간' (초)
    private var driveSec = 0L      // D 로 달린 시간 (가속 구간 계산용)
    private var angle = 0.0
    private var dir = 1
    private var sinceFlip = 0
    private var recover = 0

    private data class Pattern(val flipEvery: Int, val jerksPerMin: Int, val speedNoise: Double, val ldwEveryMin: Int)

    private val patterns = listOf(
        Pattern(flipEvery = 2, jerksPerMin = 0, speedNoise = 0.4, ldwEveryMin = 0),
        Pattern(flipEvery = 6, jerksPerMin = 1, speedNoise = 1.6, ldwEveryMin = 0),
        Pattern(flipEvery = 8, jerksPerMin = 2, speedNoise = 2.0, ldwEveryMin = 0),
        Pattern(flipEvery = 8, jerksPerMin = 2, speedNoise = 2.4, ldwEveryMin = 3),
    )

    override fun start() {
        if (job != null) return
        parkedFlow?.let { flow ->
            gearJob = scope.launch { flow.collect { parked = it } }
        }
        val tickMs = (1000 / speedFactor).toLong().coerceAtLeast(20)
        job = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (paused) continue
                val s = next()
                _samples.emit(s)
                // 시연용: 실제 1초마다 테스트 모드가 만든 값을 Logcat 에 보여 줌 (필터 tag:SCENARIO)
                if (s.timeMs % 5000 == 0L) {
                    val name = listOf("정상", "조금 졸림", "많이 졸림", "아주 졸림")[_level.value]
                    Log.i(
                        "SCENARIO",
                        "상태 $name · 기어 ${s.gear} · 속도 ${"%.1f".format(s.speedKmh)}km/h · " +
                                "핸들 ${"%+.1f".format(s.steeringDeg)}°" +
                                (s.warning?.let { " · 경고 $it" } ?: "")
                    )
                }

            }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
        gearJob?.cancel(); gearJob = null
    }

    private fun next(): VehicleSample {
        val t = tSec++
        val hour = ((startHour * 3600 + t) / 3600 % 24).toInt()
        if (parked) {
            angle = 0.0; recover = 0
            return VehicleSample(t * 1000, 0f, 0f, Gear.PARK, hourOfDay = hour)
        }
        val d = driveSec++
        // 출발 후 2분은 가속 (감지 엔진은 60km/h 이상 일정 속도일 때만 핸들·속도를 분석)
        if (d < 120) {
            val speed = 100.0 * d / 120
            return VehicleSample(t * 1000, speed.toFloat(), rnd.nextDouble(-0.3, 0.3).toFloat(), Gear.DRIVE, hourOfDay = hour)
        }
        val p = patterns[_level.value]
        val speed = 100.0 + gauss() * p.speedNoise
        when {
            recover > 0 -> {                       // 급조작 후 천천히 제자리로
                angle -= 1.4 * sign(angle)
                recover--
                if (recover == 0) angle = 0.0
            }
            p.jerksPerMin > 0 && d % (60 / p.jerksPerMin) == 0L -> {   // 깜짝 급조작 (한 번에 7도)
                angle += if (angle <= 0) 7.0 else -7.0
                recover = 5
            }
            else -> {                              // 미세 보정: flipEvery 초마다 방향을 바꿈
                sinceFlip++
                if (sinceFlip >= p.flipEvery) { dir = -dir; sinceFlip = 0 }
                angle += dir * 0.5
            }
        }
        if (abs(angle) > 15) angle = 15.0 * sign(angle)
        val warning = if (p.ldwEveryMin > 0 && d % (p.ldwEveryMin * 60) == 30L) SafetyWarning.LDW else null
        return VehicleSample(t * 1000, speed.toFloat(), angle.toFloat(), Gear.DRIVE, hourOfDay = hour, warning = warning)
    }

    /** 평균 0, 표준편차 1 정규분포 (Box-Muller) */
    private fun gauss(): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
