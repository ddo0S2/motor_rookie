package com.hyundai.motor_rookie.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Vehicle SDK 2.3.2 연동 (Seat / HVAC / Light).
 * 메서드 이름과 파라미터는 SDK 소스(Seat.kt, HVAC.kt, Light.kt)에서 확인한 것입니다.
 * 단, 공조 전원(VERIFY 표시) 두 곳은 아직 확인 전입니다.
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val vehicle = Vehicle(context.applicationContext)

    // 운전석(좌핸들) 기준. 차량이 보고한 지원 영역에 운전석이 없으면 첫 번째 지원 영역으로 바꿉니다.
    private var seatArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var hvacArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var lightArea = SeatArea(SeatValue.ROW_1_LEFT)
    // 공조 전원은 '전체 전원'이라 온도와 다른 영역으로 보고될 수 있어, 지원 영역 확인 후 정합니다.
    private var hvacPowerArea = SeatArea(SeatValue.ROW_1_LEFT)

    override suspend fun connect() {
        vehicle.initialize()
    }

    override suspend fun capabilities(): CabinCapabilities {
        // 공조 전원 지원 영역 확인 (온도 영역이 포함돼 있으면 그대로, 아니면 차량이 알려 준 첫 영역 사용)
        safely("공조 전원 지원 범위") {
            await<Unit> { ok, fail ->
                vehicle.getHVAC().checkHvacPowerCapabilities(
                    onComplete = { _, areas ->
                        hvacPowerArea = areas.firstOrNull { it == hvacArea } ?: areas.firstOrNull() ?: hvacPowerArea
                        ok(Unit)
                    },
                    onFailed = fail,
                )
            }
        }
        return CabinCapabilities(
            seatRange = safely("시트 지원 범위") {
                await<IntRange?> { ok, fail ->
                    vehicle.getSeat().checkSeatBackrestAnglePositionCapabilities(
                        onComplete = { _, bounds ->
                            val entry = bounds[seatArea]?.let { seatArea to it }
                                ?: bounds.entries.firstOrNull()?.toPair()
                            ok(entry?.let { (area, b) -> seatArea = area; b.minValue..b.maxValue })
                        },
                        onFailed = fail,
                    )
                }
            },
            temperatureRange = safely("온도 지원 범위") {
                await<ClosedFloatingPointRange<Float>?> { ok, fail ->
                    vehicle.getHVAC().checkHvacTemperatureCapabilities(
                        onComplete = { _, bounds, _ ->
                            val entry = bounds[hvacArea]?.let { hvacArea to it }
                                ?: bounds.entries.firstOrNull()?.toPair()
                            ok(entry?.let { (area, b) -> hvacArea = area; b.minValue..b.maxValue })
                        },
                        onFailed = fail,
                    )
                }
            },
            lightRange = safely("조명 지원 범위") {
                await<IntRange?> { ok, fail ->
                    vehicle.getLight().checkAmbientLightBrightnessCapabilities(
                        onComplete = { _, bounds ->
                            val entry = bounds[lightArea]?.let { lightArea to it }
                                ?: bounds.entries.firstOrNull()?.toPair()
                            ok(entry?.let { (area, b) -> lightArea = area; b.minValue..b.maxValue })
                        },
                        onFailed = fail,
                    )
                }
            },
        )
    }

    override suspend fun readCabin(): CabinState {
        val power = safely("공조 전원 읽기") { readHvacPower() }
        return CabinState(
            seatAngle = safely("시트 각도 읽기") { readSeatPosition() },
            // 공조가 꺼져 있으면 온도 값을 읽을 수 없으므로(PropertyNotAvailable) 읽지 않습니다.
            temperature = if (power == false) null else safely("온도 읽기") { readTemperature() },
            lightLevel = safely("조명 밝기 읽기") {
                await<Int?> { ok, fail ->
                    vehicle.getLight().getAmbientLightBrightness(
                        area = lightArea, onComplete = ok, onFailed = fail,
                    )
                }
            },
            hvacPower = power,
        )
    }

    override suspend fun applyCabin(state: CabinState) {
        // 1) 공조를 켜야 하면 먼저 켭니다. 꺼져 있으면 온도를 바꿀 수 없기 때문입니다.
        if (state.hvacPower == true) {
            safely("공조 켜기") { setHvacPower(true) }
        }
        // 2) 온도 (공조를 켠 직후에는 잠깐 준비 시간이 필요해 safely 의 재시도가 도움이 됩니다)
        state.temperature?.let { value ->
            safely("온도 설정") {
                await<Unit> { ok, fail ->
                    vehicle.getHVAC().setHvacTemperature(
                        area = hvacArea, value = value, unit = TemperatureUnit.CELSIUS,
                        onComplete = { _, _ -> ok(Unit) }, onFailed = fail,
                    )
                }
            }
        }
        // 3) 공조를 꺼야 하면 마지막에 끕니다.
        if (state.hvacPower == false) {
            safely("공조 끄기") { setHvacPower(false) }
        }
        state.lightLevel?.let { value ->
            safely("조명 밝기 설정") {
                await<Unit> { ok, fail ->
                    vehicle.getLight().setAmbientLightBrightness(
                        area = lightArea, value = value,
                        onComplete = { _, _ -> ok(Unit) }, onFailed = fail,
                    )
                }
            }
        }
        // 시트는 움직이는 데 시간이 걸리므로 마지막에 처리합니다.
        state.seatAngle?.let { target ->
            try {
                moveSeatTo(target)
            } catch (e: Exception) {
                Log.w(TAG, "시트 각도 설정 실패", e)
            }
        }
    }

    override fun release() {
        vehicle.release()
    }

    // ───────────── 공조 ─────────────

    private suspend fun readTemperature(): Float? = await { ok, fail ->
        vehicle.getHVAC().getHvacTemperature(
            area = hvacArea, unit = TemperatureUnit.CELSIUS,
            onComplete = ok, onFailed = fail,
        )
    }

    /** VERIFY: HVAC.kt 에서 'Power' 로 검색해 전원 상태를 읽는 메서드 이름·파라미터를 확인하세요. */
    /** 공조 전원 상태 (true = 켜짐) */
    private suspend fun readHvacPower(): Boolean? = await { ok, fail ->
        vehicle.getHVAC().getHvacPowerState(
            area = hvacPowerArea, onComplete = ok, onFailed = fail,
        )
    }

    /** 공조 전원 켜기/끄기 */
    private suspend fun setHvacPower(on: Boolean) = await<Unit> { ok, fail ->
        vehicle.getHVAC().setHvacPower(
            area = hvacPowerArea, value = on,
            onComplete = { _, _ -> ok(Unit) }, onFailed = fail,
        )
    }

    // ───────────── 시트 등받이 ─────────────

    private suspend fun readSeatPosition(): Int? = await { ok, fail ->
        vehicle.getSeat().getCurrentSeatBackrestAnglePosition(
            area = seatArea, onComplete = ok, onFailed = fail,
        )
    }

    private suspend fun moveSeat(control: MoveControl) = await<Unit> { ok, fail ->
        vehicle.getSeat().moveSeatBackrestAngle(
            area = seatArea, value = control,
            onComplete = { _, _ -> ok(Unit) }, onFailed = fail,
        )
    }

    /**
     * 등받이를 목표 위치까지 움직인 뒤 멈춥니다.
     * 값이 클수록 등받이가 서 있으므로, 목표가 현재보다 크면 FORWARD 입니다.
     */
    private suspend fun moveSeatTo(target: Int) {
        val start = withTimeout(TIMEOUT_MS) { readSeatPosition() } ?: return
        if (abs(start - target) <= SEAT_TOLERANCE) return

        val direction = if (target > start) MoveControl.FORWARD else MoveControl.BACKWARD
        withTimeout(TIMEOUT_MS) { moveSeat(direction) }
        try {
            // 목표에 닿을 때까지 위치를 확인합니다. 시간이 넘으면 그 자리에서 멈춥니다.
            withTimeoutOrNull(SEAT_MOVE_TIMEOUT_MS) {
                while (true) {
                    delay(SEAT_POLL_MS)
                    val now = withTimeout(TIMEOUT_MS) { readSeatPosition() } ?: break
                    val reached = if (direction == MoveControl.FORWARD) {
                        now >= target - SEAT_TOLERANCE
                    } else {
                        now <= target + SEAT_TOLERANCE
                    }
                    if (reached) break
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(TIMEOUT_MS) { moveSeat(MoveControl.STOP) } }
                    .onFailure { Log.w(TAG, "시트 정지 실패", it) }
            }
        }
    }

    // ───────────── 공통 ─────────────

    /** onComplete / onFailed 콜백을 suspend 함수로 바꿉니다. */
    private suspend fun <T> await(
        call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T = suspendCancellableCoroutine { cont ->
        call(
            { if (cont.isActive) cont.resume(it) },
            { if (cont.isActive) cont.resumeWithException(it) },
        )
    }

    /**
     * 미지원·실패·응답 지연이 있어도 앱이 멈추지 않도록 null 로 처리합니다.
     * 차량 서비스 연결 직후에는 첫 요청이 응답 없이 끝날 수 있어, 실패하면 잠시 쉬었다가 다시 시도합니다.
     */
    private suspend fun <T> safely(label: String, block: suspend () -> T?): T? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return withTimeout(TIMEOUT_MS) { block() }
            } catch (e: Exception) {
                Log.w(TAG, "$label 실패 (${attempt + 1}/$MAX_ATTEMPTS)", e)
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "PleosVehicle"
        const val TIMEOUT_MS = 3_000L
        const val SEAT_MOVE_TIMEOUT_MS = 15_000L
        const val SEAT_POLL_MS = 200L
        const val SEAT_TOLERANCE = 1
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}