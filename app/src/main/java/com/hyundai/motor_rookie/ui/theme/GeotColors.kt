package com.hyundai.motor_rookie.ui.theme

import androidx.compose.ui.graphics.Color
import com.hyundai.motor_rookie.fatigue.FatigueLevel

/**
 * Stitch "곁" 디자인에서 가져온 색.
 * 화면 파일에서는 Color(0xFF...)를 직접 쓰지 말고 여기 이름으로 쓴다.
 */
object GeotColors {
    val Background = Color(0xFF131417)   // 전체 바탕
    val Surface = Color(0xFF313236)      // 회색 버튼, 게이지 바탕 원, 구분선
    val Chip = Color(0xFF1F1F23)         // 단계 표시에서 "지금 단계"를 감싸는 칩
    val TextMain = Color(0xFFFFFFFF)     // 기본 글자
    val TextSub = Color(0xFFA4A8AE)      // 보조 글자 (운전 시간, 라벨)
    val Primary = Color(0xFF0082FF)      // 강조 버튼 (말 걸어줘)

    // 피로 단계 색
    val Safe = Color(0xFF55F165)         // 괜찮음 (0~39)
    val Talk = Color(0xFFFFD60A)         // 대화   (40~59)
    val Environment = Color(0xFFFF8A00)  // 환경   (60~74)
    val Rest = Color(0xFFFF4339)         // 휴식   (75~)

    fun forLevel(level: FatigueLevel): Color = when (level) {
        FatigueLevel.NORMAL -> Safe
        FatigueLevel.TALK -> Talk
        FatigueLevel.ENVIRONMENT -> Environment
        FatigueLevel.REST -> Rest
    }
}
