package com.hyundai.motor_rookie.intervention

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행 중 규제 (Pleos 운전자 주의 분산 가이드 · Driving Restriction 개발 가이드).
 *
 * Manifest 에 distractionOptimized 를 선언한 앱은 플랫폼의 CarUxRestrictions 를 받아
 * 주행 중 제한해야 하는 기능을 스스로 줄여야 한다.
 *  - 곁은 영상·애니메이션을 쓰지 않고, 주행 중 대답은 음성(또는 큰 버튼 한 번)으로 한다.
 *  - 키보드 입력이 필요한 비상연락처 등록은 주행 중에 막고 "정차 중에 추가할 수 있어요"를 보여 준다.
 *
 * MainActivity 에서 start(context) / stop().
 */
object DrivingRestriction {
    private const val TAG = "GYEOL_UX"

    private val _restricted = MutableStateFlow(false)
    /** true = 주행 중 규제 중 (키보드 입력 등 제한) */
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start(context: Context) {
        if (manager != null) return
        try {
            val c = Car.createCar(context.applicationContext)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            update(m.currentCarUxRestrictions)
            m.registerListener { update(it) }
        } catch (e: Exception) {
            Log.w(TAG, "주행 규제 상태를 받을 수 없어요", e)
        }
    }

    private fun update(r: CarUxRestrictions?) {
        val on = r?.isRequiresDistractionOptimization == true
        if (_restricted.value != on) Log.i(TAG, if (on) "주행 중 규제 시작" else "주행 중 규제 해제")
        _restricted.value = on
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }
}