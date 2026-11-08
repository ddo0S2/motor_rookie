package com.hyundai.motor_rookie.vehicle

import android.util.Log

/** 파워냅이 다루는 차량 실내 상태. null 인 항목은 읽지 못했거나 바꾸지 않는다는 뜻입니다. */
data class CabinState(
    val seatAngle: Int? = null,
    val lightLevel: Int? = null,
    val temperature: Float? = null,
    /** 공조(에어컨·히터) 전원. true = 켜짐, false = 꺼짐 */
    val hvacPower: Boolean? = null,
)

/** 차량이 보고한 지원 범위. null 이면 이 차량에서 지원하지 않는 기능입니다. */
data class CabinCapabilities(
    val seatRange: IntRange? = null,
    val lightRange: IntRange? = null,
    val temperatureRange: ClosedFloatingPointRange<Float>? = null,
)

/**
 * 앱의 나머지 코드는 이 인터페이스만 사용합니다.
 * Vehicle SDK 메서드 이름이 바뀌어도 PleosVehicleGateway 한 파일만 고치면 됩니다.
 */
interface VehicleGateway {
    suspend fun connect()
    suspend fun capabilities(): CabinCapabilities
    suspend fun readCabin(): CabinState
    /** null 이 아닌 항목만 차량에 적용합니다. */
    suspend fun applyCabin(state: CabinState)
    fun release()
}

/** SDK 연동 전에 화면 흐름만 확인할 때 쓰는 가짜 차량 */
class FakeVehicleGateway : VehicleGateway {

    private var state = CabinState(seatAngle = 8, lightLevel = 7, temperature = 22f, hvacPower = true)

    override suspend fun connect() = Unit

    override suspend fun capabilities() = CabinCapabilities(
        seatRange = 0..10,
        lightRange = 0..10,
        temperatureRange = 16f..30f,
    )

    override suspend fun readCabin() = state

    override suspend fun applyCabin(state: CabinState) {
        this.state = CabinState(
            seatAngle = state.seatAngle ?: this.state.seatAngle,
            lightLevel = state.lightLevel ?: this.state.lightLevel,
            temperature = state.temperature ?: this.state.temperature,
            hvacPower = state.hvacPower ?: this.state.hvacPower,
        )
        Log.d("FakeVehicle", "적용됨: ${this.state}")
    }

    override fun release() = Unit
}