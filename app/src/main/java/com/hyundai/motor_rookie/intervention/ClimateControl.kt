package com.hyundai.motor_rookie.intervention

import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
// 2단계 환경 개입: 온도 낮추기 + 송풍 올리기
// 실제 차량(Pleos HVAC)과 시뮬레이션을 같은 인터페이스로 사용
// ─────────────────────────────────────────────

data class ClimateState(val temperature: Float, val fanSpeed: Int)

interface ClimateController {
    suspend fun read(): ClimateState?
    /** 성공하면 null, 실패하면 이유 */
    suspend fun write(temperature: Float, fanSpeed: Int): String?
}

/** 실제 Pleos HVAC API 사용 */
class PleosClimateController(private val hvac: HVAC) : ClimateController {

    private var area: SeatArea? = null

    /** 에뮬레이터/차종이 지원하는 좌석 위치를 먼저 찾는다 (없으면 운전석) */
    private suspend fun area(): SeatArea {
        area?.let { return it }
        val found = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<SeatArea?> { cont ->
                hvac.checkHvacTemperatureCapabilities(
                    onComplete = { map -> if (cont.isActive) cont.resume(map.keys.firstOrNull()) },
                    onFailed = { if (cont.isActive) cont.resume(null) }
                )
            }
        }
        return (found ?: SeatArea(SeatValue.ROW_1_LEFT)).also { area = it }
    }

    override suspend fun read(): ClimateState? {
        val a = area()
        val temp = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Float?> { cont ->
                hvac.getHvacTemperature(
                    area = a,
                    unit = TemperatureUnit.CELSIUS,
                    onComplete = { t -> if (cont.isActive) cont.resume(t) },
                    onFailed = { if (cont.isActive) cont.resume(null) }
                )
            }
        }
        val fan = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Int?> { cont ->
                hvac.getHvacFanSpeed(
                    area = a,
                    onComplete = { f -> if (cont.isActive) cont.resume(f) },
                    onFailed = { if (cont.isActive) cont.resume(null) }
                )
            }
        }
        return if (temp != null && fan != null) ClimateState(temp, fan) else null
    }

    override suspend fun write(temperature: Float, fanSpeed: Int): String? {
        val a = area()
        var error: String? = null
        hvac.setHvacTemperature(
            area = a, value = temperature, unit = TemperatureUnit.CELSIUS,
            onFailed = { e -> error = "온도 설정 실패: ${e.message}" }
        )
        hvac.setHvacFanSpeed(
            area = a, value = fanSpeed,
            onFailed = { e -> error = (error?.plus(" / ") ?: "") + "풍량 설정 실패: ${e.message}" }
        )
        delay(500)   // 실패 콜백을 잠깐 기다림
        return error
    }
}

/** 시뮬레이션: 메모리 안의 가짜 공조 (에뮬레이터에서 HVAC가 안 될 때 시연용) */
class SimClimateController(
    private var state: ClimateState = ClimateState(temperature = 24f, fanSpeed = 2)
) : ClimateController {
    val current: ClimateState get() = state
    override suspend fun read(): ClimateState = state
    override suspend fun write(temperature: Float, fanSpeed: Int): String? {
        state = ClimateState(temperature, fanSpeed); return null
    }
}

/**
 * 기획서 4.1 "2. 환경: 에어컨 온도를 2~3℃ 낮추고 풍량을 올림"
 * 바꾸기 전 상태를 저장해두고 [되돌리기]로 복원한다 (디자인 원칙).
 */
class ClimateIntervention(
    private val controller: () -> ClimateController,
    private val tempDrop: Float = 2f,
    private val fanUp: Int = 1,
    private val minTemp: Float = 18f,
    private val maxFan: Int = 8
) {
    private var saved: ClimateState? = null
    private var usedController: ClimateController? = null
    val applied: Boolean get() = saved != null

    /** 결과 문구를 돌려준다 (배너/음성에 사용) */
    suspend fun apply(): Pair<Boolean, String> {
        if (applied) return true to "이미 공조를 조절해 둔 상태예요"
        val ctl = controller()
        val now = ctl.read() ?: return false to "공조 상태를 읽지 못했어요"
        val newTemp = max(minTemp, roundHalf(now.temperature - tempDrop))
        val newFan = (now.fanSpeed + fanUp).coerceAtMost(maxFan)
        val err = ctl.write(newTemp, newFan)
        if (err != null) return false to err
        saved = now; usedController = ctl
        return true to "에어컨 ${fmt(newTemp)}℃ · 바람 ${newFan}단계로 바꿨어요"
    }

    suspend fun restore(): String? {
        val s = saved ?: return null
        val ctl = usedController ?: controller()
        val err = ctl.write(s.temperature, s.fanSpeed)
        saved = null; usedController = null
        return err ?: "원래대로 ${fmt(s.temperature)}℃ · 바람 ${s.fanSpeed}단계로 돌려놨어요"
    }

    private fun roundHalf(v: Float) = (v * 2).roundToInt() / 2f
    private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
