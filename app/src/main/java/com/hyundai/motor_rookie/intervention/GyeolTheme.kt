package com.hyundai.motor_rookie.intervention

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────
// 곁 디자인 시스템 — Stitch HTML(code.html) 값을 그대로 옮김
//  - 기준 크기: Pleos 메인 창 1700×1184 (에뮬레이터 160dpi → 1dp = 1px = HTML px)
//  - 창이 작아지면(분할 화면 등) 전체를 같은 비율로 줄인다
// ─────────────────────────────────────────────

object GyeolColors {
    val Bg = Color(0xFF131417)          // bgDark
    val Surface = Color(0xFF1D1E21)     // surface (칩, 배너)
    val Raised = Color(0xFF313236)      // cardRaised (퀴즈 버튼, 되돌리기)
    val ButtonDark = Color(0xFF222327)  // 메인 화면 보조 버튼
    val Track = Color(0xFF2B2C30)       // 게이지·시간 막대 바탕
    val Divider = Color(0xFF44464E)
    val BannerBorder = Color(0xFF38393C)
    val Text = Color(0xFFFFFFFF)
    val Sub = Color(0xFFA4A8AE)         // textSecondary
    val SubMain = Color(0xFFA0A2AB)     // 메인/배너 보조 글자
    val Blue = Color(0xFF0082FF)
    val Green = Color(0xFF55F165)       // 0–39
    val Yellow = Color(0xFFFFD60A)      // 40–59
    val Orange = Color(0xFFFF8A00)      // 60–74
    val Red = Color(0xFFFF4339)         // 75+

    // 예전 이름 호환
    val Card get() = Surface
    val Accent get() = Blue

    fun forScore(score: Int) = when {
        score >= 75 -> Red
        score >= 60 -> Orange
        score >= 40 -> Yellow
        else -> Green
    }
}

/** Tailwind tracking-tight (-0.025em) */
val Tight = (-0.025).em

/** 창 크기에 따른 배율 (1700×1184 = 1.0) */
val LocalGyeolScale = compositionLocalOf { 1f }

@Composable
fun gdp(v: Int): Dp = (v * LocalGyeolScale.current).dp

@Composable
fun gsp(v: Int): TextUnit = (v * LocalGyeolScale.current).sp

/** 화면 루트에서 한 번 감싸면 하위 크기가 창 크기에 맞춰진다 */
@Composable
fun GyeolScaled(
    modifier: Modifier = Modifier,
    baseWidth: Float = 1700f,     // Stitch 화면 기준 크기 (1280×888로 만든 화면은 1280f, 888f)
    baseHeight: Float = 1184f,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / baseWidth, maxHeight.value / baseHeight).coerceIn(0.5f, 1.5f)
        CompositionLocalProvider(LocalGyeolScale provides scale) { content() }
    }
}

// ───────────────── 버튼 ─────────────────

/**
 * 하단 큰 버튼: h-[100px] rounded-[32px] gap-4, 아이콘 40px, 글자 30px bold
 * 배너 버튼(medium): h-[80px] rounded-[20px] px-8 gap-3, 아이콘 32px, 글자 28px bold
 */
@Composable
fun GyeolButton(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    container: Color = GyeolColors.Raised,
    medium: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (medium) gdp(80) else gdp(100)),
        shape = RoundedCornerShape(if (medium) gdp(20) else gdp(32)),
        contentPadding = PaddingValues(horizontal = if (medium) gdp(32) else gdp(24)),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = GyeolColors.Text)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (medium) gdp(32) else gdp(40)))
            Spacer(Modifier.width(if (medium) gdp(12) else gdp(16)))
        }
        Text(label, fontSize = if (medium) gsp(28) else gsp(30), fontWeight = FontWeight.Bold,
            letterSpacing = Tight, maxLines = 1)
    }
}

// ───────────────── 작은 부품 ─────────────────

/** 단계 칩: bg-[#1D1E21] px-5 py-3 rounded-full border-[#44464E]/60, 점 14px, 글자 28px bold */
@Composable
fun StageChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(GyeolColors.Surface, CircleShape)
            .border(1.dp, GyeolColors.Divider.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = gdp(20), vertical = gdp(12)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(gdp(14)).background(color, CircleShape))
        Spacer(Modifier.width(gdp(12)))
        Text(label, color = GyeolColors.Text, fontSize = gsp(28), fontWeight = FontWeight.Bold, letterSpacing = Tight)
    }
}

/** 원 안에 아이콘 */
@Composable
fun IconCircle(icon: ImageVector, iconColor: Color, bgColor: Color, size: Dp, iconSize: Dp) {
    Box(Modifier.size(size).background(bgColor, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(iconSize))
    }
}

/**
 * 원형 게이지 (2_2 화면): 440px, 반지름 192, 두께 28, 바탕 #2B2C30, 끝 둥글게
 * 가운데 숫자 116px bold, "졸음 지수" 30px medium
 */
@Composable
fun FatigueGauge(score: Int, modifier: Modifier = Modifier) {
    val color = GyeolColors.forScore(score)
    val box = gdp(440)
    Box(modifier.size(box), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 440f
            val r = 192f * k
            val w = 28f * k
            val c = Offset(size.width / 2, size.height / 2)
            val tl = Offset(c.x - r, c.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(GyeolColors.Track, 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(color, -90f, 360f * score.coerceIn(0, 100) / 100f, false, tl, sz,
                style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", color = GyeolColors.Text, fontSize = gsp(116), lineHeight = gsp(116),
                fontWeight = FontWeight.Bold, letterSpacing = Tight)
            Spacer(Modifier.height(gdp(4)))
            Text("졸음 지수", color = GyeolColors.SubMain, fontSize = gsp(30), fontWeight = FontWeight.Medium)
        }
    }
}

/** 남은 시간 막대: w-[600px] h-[8px] bg-[#313236] rounded-full */
@Composable
fun TimeBar(fraction: Float, width: Dp, track: Color = GyeolColors.Raised) {
    Box(Modifier.width(width).height(gdp(8)).background(track, CircleShape)) {
        Box(Modifier.width(width * fraction.coerceIn(0f, 1f)).height(gdp(8)).background(GyeolColors.Blue, CircleShape))
    }
}

/** 범례 (1_48 화면): 현재 단계만 밝게, 나머지 60% 투명 */
@Composable
fun StageLegend(score: Int) {
    val items = listOf(
        Triple("괜찮음", GyeolColors.Green, score < 40),
        Triple("대화", GyeolColors.Yellow, score in 40..59),
        Triple("환경", GyeolColors.Orange, score in 60..74),
        Triple("휴식", GyeolColors.Red, score >= 75)
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
        items.forEachIndexed { i, (label, color, active) ->
            if (i > 0) Box(Modifier.width(1.dp).height(gdp(16)).background(GyeolColors.Raised))
            Row(
                Modifier
                    .background(if (active) GyeolColors.Surface else Color.Transparent, CircleShape)
                    .padding(horizontal = gdp(if (active) 16 else 8), vertical = gdp(8)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(gdp(12)).background(if (active) color else color.copy(alpha = 0.6f), CircleShape))
                Spacer(Modifier.width(gdp(8)))
                Text(label, fontSize = gsp(22), fontWeight = FontWeight.Medium,
                    color = if (active) GyeolColors.Text else GyeolColors.Sub.copy(alpha = 0.6f))
            }
        }
    }
}
