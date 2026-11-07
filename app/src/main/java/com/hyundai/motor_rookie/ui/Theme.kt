package com.hyundai.motor_rookie.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Stitch 디자인 코드에서 가져온 색상 */
object NapColors {
    // 배경
    val Base = Color(0xFF131417)        // 세팅, 재출발 확인 화면
    val Black = Color(0xFF000000)       // 파워냅 진행 화면들
    val Deep = Color(0xFF0D0E11)        // 타이머 조절, 퀴즈 화면

    // 면
    val Card = Color(0xFF1D1E21)
    val QuizCard = Color(0xFF1B1B1F)
    val Pill = Color(0xFF1F1F23)
    val Button = Color(0xFF313236)
    val ButtonAlt = Color(0xFF343538)
    val Border = Color(0x6644464E)

    // 포인트
    val Blue = Color(0xFF0082FF)
    val QuizBlue = Color(0xFF4090FF)
    val OnQuizBlue = Color(0xFF002F64)
    val MicBlue = Color(0xFFAAC7FF)
    val Green = Color(0xFF55F165)
    val QuizGreen = Color(0xFF44E359)
    val CorrectBg = Color(0xFF01C03B)
    val OnCorrect = Color(0xFF00460F)
    val WrongBg = Color(0xFF93000A)
    val OnWrong = Color(0xFFFFDAD6)
    val Orange = Color(0xFFFF8A00)

    // 글자
    val White = Color(0xFFFFFFFF)
    val TimerText = Color(0xFFE3E2E6)
    val Muted = Color(0xFFA4A8AE)
    val Outline = Color(0xFF8B91A0)
    val SubText = Color(0xFFC0C6D7)
}

@Composable
fun PowerNapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NapColors.Blue,
            onPrimary = NapColors.White,
            background = NapColors.Base,
            onBackground = NapColors.White,
            surface = NapColors.Card,
            onSurface = NapColors.White,
        ),
        content = content,
    )
}
