package com.hyundai.motor_rookie.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyundai.motor_rookie.ui.theme.GeotColors

/**
 * 원형 피로 게이지 (Stitch: 지름 340, 두께 22, 숫자 84).
 *
 * @param score 0~100. 이 비율만큼 원이 채워진다.
 * @param color 단계 색 (GeotColors.forLevel)
 * @param scale 화면 크기 비율. Stitch 1280×888 화면 = 1.0
 *
 * 가이드상 주행 중 애니메이션을 줄여야 해서, 원이 차오르는 효과는 일부러 넣지 않았다.
 */
@Composable
fun FatigueGauge(score: Int, color: Color, scale: Float = 1f, modifier: Modifier = Modifier) {
    val diameter = (340 * scale).dp
    val strokeDp = (22 * scale).dp
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeDp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            // 바탕 원
            drawArc(
                color = GeotColors.Surface, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke),
            )
            // 점수만큼 채운 원 (12시 방향부터 시계 방향)
            drawArc(
                color = color, startAngle = -90f, sweepAngle = 360f * score.coerceIn(0, 100) / 100f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                fontSize = (84 * scale).sp,
                lineHeight = (88 * scale).sp,
                fontWeight = FontWeight.Bold,
                color = GeotColors.TextMain,
            )
            Text(
                text = "졸음 지수",
                fontSize = (20 * scale).sp,
                fontWeight = FontWeight.Medium,
                color = GeotColors.TextSub,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 360, backgroundColor = 0xFF131417, showBackground = true)
@Composable
private fun FatigueGaugePreview() {
    FatigueGauge(score = 48, color = GeotColors.Talk)
}
