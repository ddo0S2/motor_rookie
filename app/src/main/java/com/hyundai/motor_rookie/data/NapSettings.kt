package com.hyundai.motor_rookie.data

import android.content.Context
import com.hyundai.motor_rookie.vehicle.CabinCapabilities
import com.hyundai.motor_rookie.vehicle.CabinState
import kotlin.math.roundToInt

/**
 * 사용자가 화면에서 고른 파워냅 환경. 디자인과 같은 단위로 저장합니다.
 * 시트 100~150°, 온도 18~28℃, 조명 0~100%
 */
data class NapSettings(
    val seatDegrees: Int,
    val temperature: Int,
    val lightPercent: Int,
) {
    companion object {
        const val SEAT_MIN_DEG = 100
        const val SEAT_MAX_DEG = 150
        const val TEMP_MIN = 18
        const val TEMP_MAX = 28

        /** '기본값으로' 버튼 값 */
        val DEFAULT = NapSettings(seatDegrees = 130, temperature = 23, lightPercent = 20)
    }
}

/**
 * 화면 단위 ↔ 차량이 보고한 값 범위 변환.
 *
 * 시트: AAOS SEAT_BACKREST_ANGLE_1_POS 는 최소값이 '완전히 눕힘', 최대값이 '가장 세움'입니다.
 * 차량 값이 실제 각도가 아니므로 100°(세움)~150°(눕힘)에 직선으로 대응시킨 표시용 근사값입니다.
 * 실차에서 각도를 측정해 맞추려면 이 함수만 고치면 됩니다.
 */
object CabinMapper {

    fun toCabin(s: NapSettings, caps: CabinCapabilities) = CabinState(
        seatAngle = caps.seatRange?.let { r ->
            val reclineFraction = (s.seatDegrees - NapSettings.SEAT_MIN_DEG).toFloat() /
                (NapSettings.SEAT_MAX_DEG - NapSettings.SEAT_MIN_DEG)
            (r.last - reclineFraction * (r.last - r.first)).roundToInt()
        },
        lightLevel = caps.lightRange?.let { r ->
            (r.first + s.lightPercent / 100f * (r.last - r.first)).roundToInt()
        },
        temperature = caps.temperatureRange?.let { s.temperature.toFloat().coerceIn(it) },
    )
}

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("power_nap_settings_v2", Context.MODE_PRIVATE)

    fun load(): NapSettings? {
        if (!prefs.getBoolean(KEY_SAVED, false)) return null
        return NapSettings(
            seatDegrees = prefs.getInt(KEY_SEAT, NapSettings.DEFAULT.seatDegrees),
            temperature = prefs.getInt(KEY_TEMP, NapSettings.DEFAULT.temperature),
            lightPercent = prefs.getInt(KEY_LIGHT, NapSettings.DEFAULT.lightPercent),
        )
    }

    fun save(settings: NapSettings) {
        prefs.edit()
            .putBoolean(KEY_SAVED, true)
            .putInt(KEY_SEAT, settings.seatDegrees)
            .putInt(KEY_TEMP, settings.temperature)
            .putInt(KEY_LIGHT, settings.lightPercent)
            .apply()
    }

    private companion object {
        const val KEY_SAVED = "saved"
        const val KEY_SEAT = "seat_degrees"
        const val KEY_TEMP = "temperature"
        const val KEY_LIGHT = "light_percent"
    }
}
