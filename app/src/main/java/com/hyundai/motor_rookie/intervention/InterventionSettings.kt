package com.hyundai.motor_rookie.intervention

import android.content.Context

/**
 * 설정 화면(PPT 8번)과 공유하는 값.
 * - 공조 자동 조절 [켜기/끄기]  : "앱이 차를 직접 조작하는 기능은 사용자가 끌 수 있어야 해요"
 * - 개입 민감도 (낮음/보통/높음): 임계값을 +10 / 0 / -10 만큼 이동
 * - 퀴즈 종류
 * - 시뮬레이션 모드
 */
data class InterventionSettings(
    val climateAuto: Boolean = true,
    val windowAuto: Boolean = true,          // 창문 자동 열기
    val windowRainOff: Boolean = true,       // 비·눈이 오면 창문 자동 열기 끄기
    val simRaining: Boolean = false,
    val useRealNavi: Boolean = true,         // [안내 시작] 때 실제 Pleos 내비로 보낼지 (끄면 가짜 내비)
    val naviAutoReturn: Boolean = true,      // 내비에서 길안내가 끝나면 곁 화면으로 돌아올지
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val quizTypes: Set<QuizType> = QuizType.values().toSet(),
    val simulation: Boolean = true
)

enum class Sensitivity(val label: String, val offset: Int) {
    LOW("낮음", 10), NORMAL("보통", 0), HIGH("높음", -10)
}

object InterventionSettingsStore {
    private const val NAME = "intervention_settings"

    fun load(context: Context): InterventionSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val types = p.getStringSet("quiz_types", null)
            ?.mapNotNull { runCatching { QuizType.valueOf(it) }.getOrNull() }?.toSet()
            ?.ifEmpty { null } ?: QuizType.values().toSet()
        return InterventionSettings(
            climateAuto = p.getBoolean("climate_auto", true),
            windowAuto = p.getBoolean("window_auto", true),
            windowRainOff = p.getBoolean("window_rain_off", true),
            useRealNavi = p.getBoolean("use_real_navi", true),
            naviAutoReturn = p.getBoolean("navi_auto_return", true),
            sensitivity = runCatching { Sensitivity.valueOf(p.getString("sensitivity", "NORMAL")!!) }
                .getOrDefault(Sensitivity.NORMAL),
            quizTypes = types,
            simulation = p.getBoolean("simulation", true)
        )
    }

    fun save(context: Context, s: InterventionSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("climate_auto", s.climateAuto)
            .putBoolean("window_auto", s.windowAuto)
            .putBoolean("window_rain_off", s.windowRainOff)
            .putBoolean("use_real_navi", s.useRealNavi)
            .putBoolean("navi_auto_return", s.naviAutoReturn)
            .putString("sensitivity", s.sensitivity.name)
            .putStringSet("quiz_types", s.quizTypes.map { it.name }.toSet())
            .putBoolean("simulation", s.simulation)
            .apply()
    }
}
