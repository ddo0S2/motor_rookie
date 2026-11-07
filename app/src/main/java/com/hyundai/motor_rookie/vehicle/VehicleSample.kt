package com.hyundai.motor_rookie.vehicle

/** 기어 상태 */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 순정 안전 경고 종류 (FCW: 전방 충돌, LDW: 차선 이탈, DAW: 운전자 주의) */
enum class SafetyWarning { FCW, LDW, DAW }

/**
 * 한 순간(보통 1초마다)의 차량 상태.
 * 실제 차(Pleos SDK)든 시뮬레이션(CSV)이든 이 모양으로 바꿔서 흘려보낸다.
 *
 * @param timeMs      시각(ms). 시뮬레이션에서는 "주행 시작 후 경과 시간"이라
 *                    빨리 감기를 해도 계산이 맞는다. 벽시계를 쓰지 말 것.
 * @param hourOfDay   0~23시. 야간 가중치에 쓴다.
 * @param warning     이 순간에 새로 들어온 순정 경고 (없으면 null)
 */
data class VehicleSample(
    val timeMs: Long,
    val speedKmh: Float,
    val steeringDeg: Float,
    val gear: Gear,
    val wiperOn: Boolean = false,
    val hourOfDay: Int = 12,
    val warning: SafetyWarning? = null,
)
