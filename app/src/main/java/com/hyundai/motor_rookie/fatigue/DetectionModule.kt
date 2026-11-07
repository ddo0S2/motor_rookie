package com.hyundai.motor_rookie.fatigue

import com.hyundai.motor_rookie.vehicle.VehicleDataSource
import com.hyundai.motor_rookie.vehicle.VehicleSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 1번 담당이 팀에 내보내는 "감지 모듈" 한 덩어리.
 *
 *   val detection = DetectionModule(VehicleSources.create(context, scope, simulation = true), scope)
 *   detection.start()
 *
 *   detection.fatigue  → 피로 점수·단계 (2·3번 담당이 구독)
 *   detection.vehicle  → 원본 차량 데이터 (기어 P 확인 등에 사용)
 *   detection.engine.onTalkResult(...) / onRestTaken()
 */
class DetectionModule(
    private val source: VehicleDataSource,
    private val scope: CoroutineScope,
    val engine: FatigueEngine = FatigueEngine(),
) {
    val fatigue: StateFlow<FatigueState> get() = engine.state
    val vehicle: SharedFlow<VehicleSample> get() = source.samples

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { source.samples.collect { engine.onSample(it) } }
        source.start()
    }

    fun stop() {
        source.stop()
        job?.cancel()
        job = null
    }
}
