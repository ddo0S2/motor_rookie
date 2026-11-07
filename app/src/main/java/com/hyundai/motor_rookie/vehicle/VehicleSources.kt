package com.hyundai.motor_rookie.vehicle

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** 설정의 "시뮬레이션 모드" 값에 따라 데이터 소스를 골라 준다. */
object VehicleSources {

    fun create(
        context: Context,
        scope: CoroutineScope,
        simulation: Boolean,
        simFile: String = "sim/drowsy_drive.csv",
        speedFactor: Double = 60.0,
    ): VehicleDataSource =
        if (simulation) {
            val lines = context.assets.open(simFile).bufferedReader().use { it.readLines() }
            SimulationDataSource(lines, scope, speedFactor)
        } else {
            PleosVehicleDataSource(context, scope)
        }
}
