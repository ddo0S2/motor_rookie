package com.hyundai.motor_rookie.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
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
 * 실제 차 / 에뮬레이터에서 차량 값을 받아오는 소스.
 *
 * 값마다 가져오는 곳:
 *  - 핸들 각도: Pleos SDK  vehicle.getSteeringWheel().getCurrentSteeringWheelAngle()  (1초마다 물어봄)
 *  - 기어:      Pleos SDK  vehicle.getGear().getCurrentGearSelectionState()           (1초마다 물어봄)
 *  - 속도:      Pleos SDK에 "현재 속도" 함수가 없어서, 안드로이드 차량 표준 API
 *               (CarPropertyManager, PERF_VEHICLE_SPEED)로 받음. 값이 바뀔 때마다 알려줌.
 *  - 와이퍼, 순정 경고: 아직 연결 안 함 (없어도 점수 계산은 됨)
 *
 * 1초마다 최신 값을 묶어 VehicleSample 하나로 내보낸다.
 * (CSV 시뮬레이션도 1초에 한 줄이라, 감지 엔진 입장에서는 똑같이 보인다)
 */
class PleosVehicleDataSource(
    private val context: Context,
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

    // 속도용 (안드로이드 차량 표준 API)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSec = value.value as? Float ?: return
            onSpeed(metersPerSec * 3.6f)   // 표준 단위는 m/s → km/h 로 바꿈
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "속도 오류 이벤트: $propertyId")
        }
    }

    override fun start() {
        if (ticker != null) return
        vehicle.initialize()
        registerSpeed()
        ticker = scope.launch {
            while (isActive) {
                pollVehicle()   // 핸들·기어 물어보기 (대답은 조금 뒤 onComplete 로 옴)
                delay(1000)
                emitNow()       // 1초 동안 받은 최신 값으로 샘플 하나
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        propertyManager?.unregisterCallback(speedCallback)
        car?.disconnect()
        car = null
        propertyManager = null
        vehicle.release()
    }

    /** 속도: 값이 바뀔 때마다 speedCallback 이 불린다 */
    private fun registerSpeed() {
        try {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            val ok = pm.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            Log.d(TAG, "속도 등록: $ok")
        } catch (e: SecurityException) {
            // CAR_SPEED 권한을 아직 허용 안 했을 때
            Log.e(TAG, "속도 권한 없음 (CAR_SPEED). 앱 권한 허용 후 '처음부터 다시'", e)
        } catch (e: Exception) {
            Log.e(TAG, "속도 등록 실패", e)
        }
    }

    /** 핸들·기어: Pleos SDK 에 1초마다 현재 값을 물어본다 */
    private fun pollVehicle() {
        vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
            onComplete = { angle -> if (angle != null) onSteering(angle) },
            onFailed = { Log.w(TAG, "핸들 읽기 실패: $it") },
        )
        vehicle.getGear().getCurrentGearSelectionState(
            onComplete = { state -> if (state != null) onGear(toGear(state.toString())) },
            onFailed = { Log.w(TAG, "기어 읽기 실패: $it") },
        )
    }

    /**
     * SDK 기어 값 → 우리 Gear.
     * SDK 기어 타입 이름을 몰라도 되게, 글자(toString)로 판단한다.
     * Logcat 에 "gearRaw=..." 로 실제 글자가 찍히니, 안 맞으면 여기만 고치면 된다.
     */
    private fun toGear(raw: String): Gear {
        if (raw != lastGearRaw) { Log.d(TAG, "gearRaw=$raw"); lastGearRaw = raw }
        val s = raw.uppercase()
        return when {
            "UNKNOWN" in s -> Gear.UNKNOWN   // GEAR_UNKNOWN 이 N 으로 끝나서 중립으로 오인되지 않게 먼저 거름
            "PARK" in s || s.endsWith("P") -> Gear.PARK
            "REVERSE" in s || s.endsWith("R") -> Gear.REVERSE
            "NEUTRAL" in s || s.endsWith("N") -> Gear.NEUTRAL
            "DRIVE" in s || s.endsWith("D") -> Gear.DRIVE
            else -> Gear.UNKNOWN
        }
    }
    private var lastGearRaw: String? = null

    // ── 값이 들어오면 최신 값만 갱신 ──
    fun onSpeed(kmh: Float) { speedKmh = kmh; Log.d(TAG, "speed=$kmh") }
    fun onSteering(deg: Float) { steeringDeg = deg; Log.d(TAG, "steer=$deg") }
    fun onGear(g: Gear) { if (g != gear) Log.d(TAG, "gear=$g"); gear = g }
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
