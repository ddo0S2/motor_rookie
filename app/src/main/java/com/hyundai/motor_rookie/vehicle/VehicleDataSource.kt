package com.hyundai.motor_rookie.vehicle

import kotlinx.coroutines.flow.SharedFlow

/**
 * 차량 데이터를 "어디서 받아오는지"를 숨기는 인터페이스.
 *
 * - [PleosVehicleDataSource]     : 실제 차 / 에뮬레이터 (Pleos Vehicle SDK)
 * - [SimulationDataSource]       : 녹화해 둔 주행 데이터(CSV)를 재생 (시연용)
 *
 * 피로 감지(FatigueEngine)와 다른 팀원 코드는 이 인터페이스만 보고 동작하므로,
 * 설정의 "시뮬레이션 모드" 스위치 하나로 둘을 바꿔 끼울 수 있다.
 */
interface VehicleDataSource {
    /** 약 1초마다 최신 차량 상태가 흘러나온다. 마지막 값 1개는 새 구독자에게도 바로 전달된다. */
    val samples: SharedFlow<VehicleSample>

    fun start()
    fun stop()
}
