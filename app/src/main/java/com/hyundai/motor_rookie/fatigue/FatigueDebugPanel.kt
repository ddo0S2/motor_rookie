package com.hyundai.motor_rookie.fatigue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 튜닝·시연용 디버그 화면: 점수와 각 신호 값이 어떻게 움직이는지 보여준다.
 * 설정 > 시뮬레이션 모드에서만 띄우고, PT 때 "이렇게 계산된다"를 보여줄 때도 쓴다.
 */
@Composable
fun FatigueDebugPanel(state: FatigueState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier
            .background(c.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.score}", fontSize = 56.sp, fontWeight = FontWeight.Black, color = c.onSurface)
            Text(state.level.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)
        }
        Text(
            "운전 ${state.drivingMinutes}분 · m ×${"%.2f".format(state.multiplier)} " +
                state.reasons.joinToString(" · ").ifEmpty { "" },
            color = c.onSurfaceVariant, fontSize = 16.sp,
        )
        SignalBar("s_time  연속 주행", state.signals.time)
        SignalBar("s_steer 핸들 보정", state.signals.steer)
        SignalBar("s_speed 속도 변동", state.signals.speed)
        SignalBar("s_talk  대화 반응", state.signals.talk)
        SignalBar("s_signal 순정 경고", state.signals.signal)
        Text(
            (if (state.baselineReady) "기준선 학습 완료" else "기준선 학습 중 (처음 15분)") +
                (if (state.parked) " · 주차 중" else "") +
                (if (state.noResponseCount > 0) " · 무응답 ${state.noResponseCount}회" else ""),
            color = c.onSurfaceVariant, fontSize = 14.sp,
        )
    }
}

@Composable
private fun SignalBar(label: String, value: Double) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.width(150.dp), fontSize = 14.sp, color = c.onSurface)
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.outline),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                    .background(c.primary),
            )
        }
        Text("%.2f".format(value), modifier = Modifier.width(44.dp), fontSize = 14.sp, color = c.onSurface)
    }
}

@Preview(widthDp = 520, heightDp = 420)
@Composable
private fun FatigueDebugPanelPreview() {
    FatigueDebugPanel(
        FatigueState(
            score = 62, level = FatigueLevel.ENVIRONMENT,
            signals = FatigueSignals(0.9, 0.55, 0.3, 0.4, 0.5),
            multiplier = 1.32, reasons = listOf("야간", "고속 직진"), drivingMinutes = 108, baselineReady = true,
        ),
    )
}
