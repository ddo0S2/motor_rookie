package com.hyundai.motor_rookie.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * 실제 차 / 에뮬레이터에서 Pleos Vehicle SDK로 값을 받아오는 소스.
 *
 * 구조:
 *  1) SDK Callback이 올 때마다 onSpeed / onSteering / onGear ... 로 "최신 값"만 갱신
 *  2) 1초마다 최신 값을 묶어 VehicleSample 하나로 내보냄
 *     (Callback은 값이 바뀔 때만 오기 때문에, 시간이 일정하게 흐르도록 1초 간격으로 묶는다)
 *
 * TODO(1번 담당): registerCallbacks() 안의 SDK 호출을 채우기.
 *   `vehicle.get` 까지 입력 → 자동완성에서 핸들/속도/기어/와이퍼/Safety 항목 찾기
 *   → register... 함수 안에서 아래 onXxx() 를 호출하면 끝.
 */
class PleosVehicleDataSource(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleDataSource {

    private val vehicle = Vehicle(context)
    private val _samples = MutableSharedFlow<VehicleSample>(replay = 1, extraBufferCapacity = 64)
    override val samples: SharedFlow<VehicleSample> = _samples.asSharedFlow()
    private var ticker: Job? = null
    private val startMs = System.currentTimeMillis()

    @Volatile private var speedKmh = 0f
    @Volatile private var steeringDeg = 0f
    @Volatile private var gear = Gear.UNKNOWN
    @Volatile private var wiperOn = false
    @Volatile private var pendingWarning: SafetyWarning? = null

    override fun start() {
        if (ticker != null) return
        vehicle.initialize()
        registerCallbacks()
        ticker = scope.launch {
            while (isActive) {
                emitNow()
                delay(1000)
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        // TODO(1번 담당): register 했던 Callback 들을 unregister
        vehicle.release()
    }

    private fun registerCallbacks() {
        // ───────────── 아래 예시 모양으로 채우기 (함수 이름은 자동완성으로 확인) ─────────────
        //
        // vehicle.getSteeringWheel().registerXxxAngle(
        //     onChanged = { value -> onSteering(value) },
        //     onFailed  = { e -> Log.e(TAG, "핸들 실패: ${e.message}") }
        // )
        // vehicle.getXxx().registerSpeed(...)      → onSpeed(km/h)
        // vehicle.getXxx().registerGear(...)       → onGear(값을 Gear 로 변환)
        // vehicle.getXxx().registerWiper(...)      → onWiper(켜짐 여부)
        // vehicle.getSafety().registerXxx(...)     → onWarning(SafetyWarning.LDW 등)
        //
        // 확인 방법: 에뮬레이터 Terminal 에서
        //   adb shell cmd car_service inject-vhal-event 0x11600207 0 90    (속도 90, 단위 확인 필요)
        //   adb shell cmd car_service inject-vhal-event 0x11600209 0 5     (핸들 5도)
        // 를 넣고 Logcat 의 "VehicleSrc" 로그가 따라 바뀌는지 본다.
        Log.d(TAG, "registerCallbacks: TODO")
    }

    // ── SDK Callback 에서 부르는 함수들 ──
    fun onSpeed(kmh: Float) { speedKmh = kmh; Log.d(TAG, "speed=$kmh") }
    fun onSteering(deg: Float) { steeringDeg = deg; Log.d(TAG, "steer=$deg") }
    fun onGear(g: Gear) { gear = g; Log.d(TAG, "gear=$g") }
    fun onWiper(on: Boolean) { wiperOn = on }
    fun onWarning(w: SafetyWarning) { pendingWarning = w; Log.d(TAG, "warning=$w") }

    private suspend fun emitNow() {
        val w = pendingWarning
        pendingWarning = null
        _samples.emit(
            VehicleSample(
                timeMs = System.currentTimeMillis() - startMs,
                speedKmh = speedKmh,
                steeringDeg = steeringDeg,
                gear = gear,
                wiperOn = wiperOn,
                hourOfDay = LocalTime.now().hour,
                warning = w,
            )
        )
    }

    private companion object { const val TAG = "VehicleSrc" }
}
