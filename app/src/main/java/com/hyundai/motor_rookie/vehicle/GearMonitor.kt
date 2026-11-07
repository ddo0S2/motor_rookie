package com.hyundai.motor_rookie.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 기어 상태는 AAOS 표준 데이터이므로 Pleos SDK 가 아닌 AAOS Car API 로 읽습니다.
 *
 * 차량 서비스(car_service)가 아직 준비되지 않았거나 재시작되는 경우에도 앱이 종료되지 않도록,
 * 기다리지 않고 연결을 요청한 뒤 준비되었다는 알림을 받으면 그때 기어 정보를 구독합니다.
 */
class GearMonitor(context: Context) {

    private val appContext = context.applicationContext

    /** P단이면 true. 기어가 바뀔 때마다 값을 내보냅니다. */
    fun parkedFlow(): Flow<Boolean> = callbackFlow {
        var propertyManager: CarPropertyManager? = null

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                trySend(value.value as? Int == VehicleGear.GEAR_PARK)
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
        }

        val car: Car? = Car.createCar(
            appContext,
            null,
            Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
        ) { connectedCar, ready ->
            if (ready) {
                val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                propertyManager = manager
                val registered = manager?.registerCallback(
                    callback,
                    VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE,
                ) ?: false
                if (!registered) Log.w(TAG, "GEAR_SELECTION 구독 실패")
            } else {
                // 차량 서비스가 재시작되는 중. 다시 준비되면 이 블록이 ready = true 로 다시 호출됩니다.
                Log.w(TAG, "차량 서비스 연결 끊김, 재연결 대기 중")
                propertyManager = null
            }
        }
        if (car == null) Log.w(TAG, "차량 서비스에 연결할 수 없어요")

        awaitClose {
            runCatching { propertyManager?.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
        }
    }.distinctUntilChanged()

    /** 연결 해제는 parkedFlow 구독이 끝날 때 자동으로 처리됩니다. */
    fun release() = Unit

    private companion object {
        const val TAG = "GearMonitor"
    }
}