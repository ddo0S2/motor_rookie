@file:OptIn(ExperimentalMaterial3Api::class)

package com.hyundai.motor_rookie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AirlineSeatReclineExtra
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyundai.motor_rookie.data.NapSettings
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler

/** Stitch 디자인 캔버스 크기 */
private val DesignWidth = 1280.dp
private val DesignHeight = 888.dp

@Composable
fun PowerNapApp(onExit: () -> Unit, vm: PowerNapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    // 설정 화면이 아닐 때는 뒤로 가기로 앱이 닫히지 않고 이전 단계로 이동합니다.
    BackHandler(enabled = s.screen != Screen.SETTINGS) { vm.onBack() }
    val background = when (s.screen) {
        Screen.NAP_READY, Screen.TIMER_CONFIRM, Screen.NAPPING -> NapColors.Black
        Screen.TIMER_SETUP, Screen.QUIZ -> NapColors.Deep
        else -> NapColors.Base
    }
    PowerNapTheme {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(background),
            contentAlignment = Alignment.Center,
        ) {
            // 디자인 캔버스(1280×888)를 그대로 그린 뒤 디스플레이 크기에 맞게 통째로 확대·축소합니다.
            // 터치 좌표도 함께 변환되므로 버튼은 보이는 위치 그대로 동작합니다.
            val scale = min(maxWidth / DesignWidth, maxHeight / DesignHeight)
            Box(
                Modifier
                    .requiredSize(DesignWidth, DesignHeight)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .padding(32.dp),
            ) {
                when (s.screen) {
                    Screen.SETTINGS -> SettingsScreen(s, vm, onExit)
                    Screen.NAP_READY -> NapReadyScreen(s, vm)
                    Screen.TIMER_SETUP -> TimerSetupScreen(s, vm)
                    Screen.TIMER_CONFIRM -> TimerConfirmScreen(s, vm)
                    Screen.NAPPING -> NappingScreen(s, vm)
                    Screen.QUIZ -> QuizScreen(s, vm)
                    Screen.QUIZ_FAILED -> QuizFailedScreen(s, vm)
                    Screen.STRETCHING -> StretchingScreen(s, vm)
                    Screen.PASSED -> PassedScreen(s, vm)
                }
            }
        }
    }
}

// ═════════════ 1. 파워냅 세팅 (Settings) ═════════════

@Composable
private fun SettingsScreen(s: UiState, vm: PowerNapViewModel, onExit: () -> Unit) {
    val d = s.draft
    val caps = s.capabilities
    val canStartNow = s.isParked && s.saved != null

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(NapColors.Button)
                    .clickable(onClick = onExit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ChevronLeft, "뒤로 가기", tint = NapColors.White, modifier = Modifier.size(32.dp))
            }
            HSpace(16)
            Text("파워냅 세팅", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
            HSpace(16)
            Text(
                s.notice ?: "원하는 대로 시트와 온도 및 조명을 조절해 주세요",
                fontSize = 20.sp,
                color = if (s.notice != null) NapColors.Blue else NapColors.Muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = when {
                    canStartNow -> "Ⓟ 정차 중 · 바로 시작"
                    s.isParked -> "Ⓟ 정차 중 설정"
                    else -> "P단에서 자동 시작"
                },
                shape = RoundedCornerShape(8.dp),
                onClick = if (canStartNow) vm::startNapSession else null,
            )
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 왼쪽: 등받이 각도 그림
            Column(
                Modifier.weight(0.38f).fillMaxHeight()
                    .background(NapColors.Card, RoundedCornerShape(24.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SeatIllustration(d.seatDegrees, Modifier.size(270.dp))
                VSpace(24)
                Text("${d.seatDegrees}°", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
                VSpace(6)
                Text("등받이 기울기", fontSize = 20.sp, color = NapColors.Muted)
            }

            // 오른쪽: 슬라이더 3개
            Column(Modifier.weight(0.62f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SliderCard(
                    Modifier.weight(1f), Icons.Filled.AirlineSeatReclineNormal, "시트 각도", "${d.seatDegrees}°",
                    d.seatDegrees.toFloat(),
                    NapSettings.SEAT_MIN_DEG.toFloat()..NapSettings.SEAT_MAX_DEG.toFloat(),
                    supported = caps.seatRange != null,
                ) { vm.setSeatDegrees(it.roundToInt()) }
                SliderCard(
                    Modifier.weight(1f), Icons.Filled.Thermostat, "온도", "${d.temperature}℃",
                    d.temperature.toFloat(),
                    NapSettings.TEMP_MIN.toFloat()..NapSettings.TEMP_MAX.toFloat(),
                    supported = caps.temperatureRange != null,
                ) { vm.setTemperature(it.roundToInt()) }
                SliderCard(
                    Modifier.weight(1f), Icons.Filled.Lightbulb, "조명 밝기", "${d.lightPercent}%",
                    d.lightPercent.toFloat(), 0f..100f,
                    supported = caps.lightRange != null,
                ) { vm.setLightPercent(it.roundToInt()) }
            }
        }

        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton("기본값으로", Icons.Filled.RestartAlt, NapColors.Button, vm::resetToDefaults, Modifier.weight(1f))
            ActionButton(
                text = if (s.saveFlash) "저장 완료" else "저장",
                icon = Icons.Filled.Check,
                container = if (s.saveFlash) NapColors.Green else NapColors.Blue,
                content = if (s.saveFlash) NapColors.Base else NapColors.White,
                onClick = vm::saveSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SliderCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    supported: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(NapColors.Card, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = NapColors.White, modifier = Modifier.size(28.dp))
            HSpace(12)
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
            if (!supported) {
                HSpace(12)
                Text(
                    "이 차량에서는 자동으로 조절되지 않아요",
                    fontSize = 16.sp, color = NapColors.Muted, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.Blue)
        }
        VSpace(18)
        // 설정값은 차량 지원 여부와 관계없이 조절·저장할 수 있습니다.
        // 지원하지 않는 항목은 파워냅 실행 때 차량 제어만 건너뜁니다.
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            thumb = {
                Box(Modifier.size(40.dp).background(NapColors.White, CircleShape))
            },
            track = { _ ->
                val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(16.dp).background(NapColors.Button, CircleShape)) {
                    Box(
                        Modifier.fillMaxWidth(fraction).fillMaxHeight()
                            .background(NapColors.Blue, CircleShape),
                    )
                }
            },
        )
    }
}

/** 디자인의 시트 SVG를 그대로 옮기고, 등받이를 설정 각도만큼 회전합니다. (130° → 40° 회전) */
@Composable
private fun SeatIllustration(degrees: Int, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 240f, pivot = Offset.Zero) {
            val base = Color(0xFF44464E)
            drawLine(base, Offset(50f, 186f), Offset(160f, 186f), 2.5f, StrokeCap.Round)
            drawLine(base, Offset(72f, 186f), Offset(72f, 166f), 2.5f, StrokeCap.Round)
            drawLine(base, Offset(138f, 186f), Offset(138f, 166f), 2.5f, StrokeCap.Round)

            val cushion = Path().apply {
                moveTo(64f, 162f)
                cubicTo(86f, 164f, 130f, 164f, 148f, 152f)
                cubicTo(158f, 145f, 164f, 153f, 162f, 163f)
                cubicTo(156f, 172f, 134f, 175f, 78f, 174f)
                cubicTo(66f, 174f, 60f, 167f, 64f, 162f)
                close()
            }
            drawPath(cushion, NapColors.White, style = Stroke(3f, join = StrokeJoin.Round))

            rotate(degrees - 90f, pivot = Offset(82f, 154f)) {
                val backrest = Path().apply {
                    moveTo(82f, 154f)
                    lineTo(82f, 72f)
                    cubicTo(82f, 62f, 94f, 62f, 94f, 72f)
                    lineTo(94f, 154f)
                    close()
                }
                drawPath(backrest, NapColors.White, style = Stroke(3f, join = StrokeJoin.Round))
                drawLine(NapColors.White, Offset(85f, 62f), Offset(85f, 46f), 2.5f, StrokeCap.Round)
                drawLine(NapColors.White, Offset(91f, 62f), Offset(91f, 46f), 2.5f, StrokeCap.Round)
                drawRoundRect(
                    NapColors.White, Offset(79f, 16f), Size(18f, 30f), CornerRadius(9f), style = Stroke(3f),
                )
            }
        }
    }
}

// ═════════════ 2. P단 전환 직후 (NAP_READY) ═════════════

@Composable
private fun NapReadyScreen(s: UiState, vm: PowerNapViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bedtime, null, tint = NapColors.White, modifier = Modifier.size(32.dp))
            HSpace(12)
            Text("파워냅", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
            Spacer(Modifier.weight(1f))
            StatusChip("정차 중 · P")
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.weight(0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
                TimerGauge(340.dp, 12.dp, progress = null, onClick = vm::openTimerSetup) {
                    Text(
                        clock(s.timerMinutes * 60), fontSize = 84.sp,
                        fontWeight = FontWeight.ExtraBold, color = NapColors.White,
                    )
                    VSpace(12)
                    Text("알람까지", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = NapColors.Muted)
                }
                VSpace(16)
                Text("원을 누르면 1분 단위로 조절할 수 있어요", fontSize = 18.sp, color = NapColors.Muted)
            }
            Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("시간을 골라 주세요", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
                PresetButtons(s.timerMinutes, vm::selectPreset, height = 96.dp, radius = 24.dp)
                AppliedSettingsCard(s.saved ?: NapSettings.DEFAULT)
            }
        }

        ActionButton("시작", Icons.Filled.PlayArrow, NapColors.Blue, vm::startNap, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AppliedSettingsCard(settings: NapSettings) {
    Column(
        Modifier.fillMaxWidth().background(NapColors.Card, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = NapColors.Green, modifier = Modifier.size(28.dp))
            HSpace(12)
            Text("파워냅 세팅 전환 완료", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
        }
        Row(Modifier.fillMaxWidth()) {
            Telemetry(Icons.Filled.AirlineSeatReclineNormal, "시트 눕힘 ${settings.seatDegrees}°", Modifier.weight(1f))
            Telemetry(Icons.Filled.Lightbulb, "조명 ${settings.lightPercent}%", Modifier.weight(1f))
            Telemetry(Icons.Filled.Thermostat, "온도 ${settings.temperature}℃", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Telemetry(icon: ImageVector, text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = NapColors.Muted, modifier = Modifier.size(24.dp))
        HSpace(10)
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = NapColors.Muted, maxLines = 1)
    }
}

// ═════════════ 3. 타이머 조절 (TIMER_SETUP) ═════════════

@Composable
private fun TimerSetupScreen(s: UiState, vm: PowerNapViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        NapHeader()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TimerGauge(440.dp, 16.dp, progress = s.timerMinutes / NapRules.MAX_MINUTES.toFloat()) {
                BigClock(clock(s.timerMinutes * 60))
                Text("알람까지", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Outline)
            }
            VSpace(24)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AirlineSeatReclineExtra, null, tint = NapColors.Outline, modifier = Modifier.size(28.dp))
                HSpace(8)
                Text("시트를 눕히고 조명을 낮출게요", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Outline)
            }
            VSpace(24)
            Row(Modifier.width(672.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(-5, -1, 1, 5).forEach { delta ->
                    val enabled = if (delta < 0) s.timerMinutes > NapRules.MIN_MINUTES
                    else s.timerMinutes < NapRules.MAX_MINUTES
                    ActionButton(
                        text = if (delta > 0) "+${delta}분" else "−${-delta}분",
                        icon = null,
                        container = NapColors.ButtonAlt,
                        onClick = { vm.adjustTimer(delta) },
                        modifier = Modifier.weight(1f),
                        height = 80.dp, radius = 16.dp, fontSize = 30.sp,
                        content = NapColors.TimerText, weight = FontWeight.Medium,
                        enabled = enabled,
                    )
                }
            }
        }
        Row(Modifier.width(768.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            ActionButton(
                "취소", Icons.Filled.Close, NapColors.ButtonAlt, vm::closeTimerSetup, Modifier.weight(1f),
                height = 100.dp, radius = 32.dp, fontSize = 40.sp, content = NapColors.TimerText,
                weight = FontWeight.SemiBold,
            )
            ActionButton(
                "시작", Icons.Outlined.PlayArrow, NapColors.Blue, vm::confirmTimer, Modifier.weight(1f),
                height = 100.dp, radius = 32.dp, fontSize = 40.sp, weight = FontWeight.SemiBold,
            )
        }
    }
}

// ═════════════ 4. 시간 확인 (TIMER_CONFIRM) ═════════════

@Composable
private fun TimerConfirmScreen(s: UiState, vm: PowerNapViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        NapHeader()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TimerGauge(
                400.dp, 16.dp,
                progress = s.timerMinutes / NapRules.MAX_MINUTES.toFloat(),
                onClick = vm::openTimerSetup,
            ) {
                BigClock(clock(s.timerMinutes * 60))
                Text("알람까지", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Outline)
            }
            VSpace(24)
            AppliedStatusLine(s.saved ?: NapSettings.DEFAULT)
            VSpace(24)
            PresetButtons(
                s.timerMinutes, vm::selectPreset, height = 100.dp, radius = 28.dp,
                modifier = Modifier.width(672.dp),
            )
        }
        ActionButton(
            "시작", Icons.Outlined.PlayArrow, NapColors.Blue, vm::startNap, Modifier.width(672.dp),
            height = 100.dp, radius = 32.dp, fontSize = 32.sp,
        )
    }
}

// ═════════════ 5. 파워냅 진행 중 (NAPPING) ═════════════

@Composable
private fun NappingScreen(s: UiState, vm: PowerNapViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        NapHeader()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val progress = if (s.napTotalSec > 0) s.remainingSec / s.napTotalSec.toFloat() else 0f
            TimerGauge(440.dp, 16.dp, progress = progress) {
                BigClock(clock(s.remainingSec))
                Text("남은 시간", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Outline)
            }
            VSpace(32)
            AppliedStatusLine(s.saved ?: NapSettings.DEFAULT)
        }
        Row(Modifier.width(672.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ActionButton(
                "5분 연장", Icons.Filled.Add, NapColors.Button, vm::extendNap, Modifier.weight(1f),
                height = 100.dp, radius = 32.dp, fontSize = 30.sp,
            )
            ActionButton(
                "지금 깨우기", Icons.Filled.AlarmOn, NapColors.Blue, vm::wakeNow, Modifier.weight(1f),
                height = 100.dp, radius = 32.dp, fontSize = 30.sp,
            )
        }
    }
}

// ═════════════ 6. 각성 확인 퀴즈 (QUIZ) ═════════════

@Composable
private fun QuizScreen(s: UiState, vm: PowerNapViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.background(NapColors.Pill, CircleShape).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, null, tint = NapColors.QuizGreen, modifier = Modifier.size(28.dp))
                HSpace(8)
                Text("휴식 완료 · 출발 준비", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.TimerText)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.LocalCafe, null, tint = NapColors.SubText, modifier = Modifier.size(26.dp))
            HSpace(4)
            Text("파워냅 ${s.napElapsedMin}분 경과", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.SubText)
            HSpace(24)
            Text("|", fontSize = 24.sp, color = NapColors.Outline)
            HSpace(24)
            Text(
                if (s.isParked) "PARKED" else "DRIVING",
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.TimerText,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("다시 출발할 준비 되셨나요?", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = NapColors.TimerText)
            Text("간단한 질문 하나에 답해 주세요", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = NapColors.SubText)
            VSpace(32)
            Column(
                Modifier.width(896.dp).background(NapColors.QuizCard, RoundedCornerShape(32.dp))
                    .padding(horizontal = 48.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, tint = NapColors.SubText, modifier = Modifier.size(28.dp))
                    HSpace(8)
                    Text(
                        "각성 확인 퀴즈 ${s.quizIndex + 1} / ${s.quizTotal}",
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.SubText,
                    )
                }
                VSpace(16)
                Text(
                    s.quizDisplay,
                    fontSize = if (s.quizDisplay.length <= 9) 96.sp else 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = NapColors.TimerText,
                    maxLines = 1,
                )
                VSpace(24)
                VoiceStatus(s)
                if (s.quizChoices.isNotEmpty()) {
                    VSpace(32)
                    Row(Modifier.width(576.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        s.quizChoices.forEach { choice ->
                            ActionButton(
                                text = choice, icon = null, container = NapColors.ButtonAlt,
                                onClick = { vm.answerByTouch(choice) },
                                modifier = Modifier.weight(1f),
                                height = 88.dp, radius = 12.dp, fontSize = 40.sp,
                                content = NapColors.TimerText, weight = FontWeight.SemiBold,
                                enabled = s.quizPhase == QuizPhase.LISTENING,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            ActionButton(
                "10분 더 쉬기", Icons.Outlined.Snooze, NapColors.ButtonAlt, vm::restMore, Modifier.width(340.dp),
                height = 100.dp, radius = 32.dp, fontSize = 30.sp, content = NapColors.TimerText,
            )
            ActionButton(
                "출발할게요", Icons.Outlined.Navigation, NapColors.QuizBlue, vm::departWithoutQuiz, Modifier.width(340.dp),
                height = 100.dp, radius = 32.dp, fontSize = 30.sp, content = NapColors.OnQuizBlue,
            )
        }
    }
}

private data class VoiceLook(
    val background: Color,
    val icon: ImageVector,
    val iconTint: Color,
    val text: String,
    val textColor: Color,
)

@Composable
private fun VoiceStatus(s: UiState) {
    val heard = s.heardText
    val look = when (s.quizPhase) {
        QuizPhase.INTRO, QuizPhase.ASKING -> VoiceLook(
            NapColors.Pill, Icons.AutoMirrored.Filled.VolumeUp, NapColors.QuizBlue,
            "질문을 잘 들어 주세요", NapColors.MicBlue,
        )
        QuizPhase.LISTENING -> VoiceLook(
            NapColors.Pill, Icons.Filled.Mic, NapColors.QuizBlue,
            if (heard.isBlank()) "음성으로 정답을 말씀하세요 · ${s.listenSecLeft}초"
            else "“$heard” · ${s.listenSecLeft}초",
            NapColors.MicBlue,
        )
        QuizPhase.CORRECT -> VoiceLook(
            NapColors.CorrectBg, Icons.Filled.CheckCircle, NapColors.OnCorrect,
            "정답이에요", NapColors.OnCorrect,
        )
        QuizPhase.WRONG -> VoiceLook(
            NapColors.WrongBg, Icons.Filled.Warning, NapColors.OnWrong,
            if (heard.isBlank()) "시간 안에 답을 듣지 못했어요" else "“$heard”는 정답이 아니에요",
            NapColors.OnWrong,
        )
    }
    Row(
        Modifier.background(look.background, CircleShape).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.icon, null, tint = look.iconTint, modifier = Modifier.size(32.dp))
        HSpace(8)
        Text(look.text, fontSize = 26.sp, color = look.textColor, maxLines = 1)
    }
}

// ═════════════ 7. 미통과 (QUIZ_FAILED) ═════════════

@Composable
private fun QuizFailedScreen(s: UiState, vm: PowerNapViewModel) {
    Column(Modifier.fillMaxSize()) {
        RestartHeader {
            Text("${s.quizTotal} / ${s.quizTotal}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Muted)
            HSpace(16)
            repeat(s.quizTotal) { i ->
                if (i > 0) HSpace(8)
                Box(Modifier.size(8.dp).background(if (i == s.quizTotal - 1) NapColors.Blue else NapColors.Muted, CircleShape))
            }
        }

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Box(Modifier.weight(0.38f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(250.dp).background(NapColors.Orange.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = NapColors.Orange, modifier = Modifier.size(140.dp))
                }
            }
            Column(Modifier.weight(0.62f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("5분만 더 쉬어요", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = NapColors.White)
                    Text("아직 반응이 조금 느려요", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = NapColors.Muted)
                }
                ResultChips(s.quizResults, failColor = NapColors.Orange)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, null, tint = NapColors.Orange, modifier = Modifier.size(28.dp))
                    HSpace(12)
                    Text(
                        "${shortClock(s.retrySecLeft)} 후에 다시 확인할게요",
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.White,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton("스트레칭 안내", Icons.Filled.AccessibilityNew, NapColors.Blue, vm::openStretching, Modifier.weight(1f))
            ActionButton("다시 확인", Icons.Filled.Refresh, NapColors.Button, vm::retryQuizNow, Modifier.weight(1f))
        }
    }
}

// ═════════════ 8. 스트레칭 안내 (디자인 없음: 재출발 확인 화면 스타일로 구성) ═════════════

private val stretchingSteps = listOf(
    "목 돌리기" to "고개를 천천히 오른쪽으로 다섯 번, 왼쪽으로 다섯 번 돌려요.",
    "어깨 으쓱하기" to "어깨를 귀까지 끌어올렸다가 툭 내려놓기를 다섯 번 해요.",
    "손목·손가락 풀기" to "주먹을 꽉 쥐었다가 활짝 펴기를 열 번 해요.",
    "허리 비틀기" to "등받이를 잡고 상체를 좌우로 천천히 비틀어 10초씩 버텨요.",
    "깊게 숨쉬기" to "코로 4초 들이마시고 입으로 6초 내쉬기를 세 번 해요.",
)

@Composable
private fun StretchingScreen(s: UiState, vm: PowerNapViewModel) {
    Column(Modifier.fillMaxSize()) {
        RestartHeader(title = "스트레칭 안내") {
            StatusChip("다음 확인까지 ${shortClock(s.retrySecLeft)}")
        }
        VSpace(20)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stretchingSteps.forEachIndexed { i, (name, how) ->
                Row(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(NapColors.Card, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).background(NapColors.Blue.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NapColors.Blue)
                    }
                    HSpace(20)
                    Column {
                        Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
                        Text(how, fontSize = 20.sp, color = NapColors.Muted)
                    }
                }
            }
        }
        VSpace(20)
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton("돌아가기", Icons.Filled.ChevronLeft, NapColors.Button, vm::closeStretching, Modifier.weight(1f))
            ActionButton("다시 확인", Icons.Filled.Refresh, NapColors.Blue, vm::retryQuizNow, Modifier.weight(1f))
        }
    }
}

// ═════════════ 9. 통과 (DEPART) ═════════════

@Composable
private fun PassedScreen(s: UiState, vm: PowerNapViewModel) {
    Column(Modifier.fillMaxSize()) {
        RestartHeader {
            Row(
                Modifier.border(1.dp, NapColors.Border, CircleShape).background(NapColors.Card, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(s.quizTotal) { i ->
                    if (i > 0) HSpace(8)
                    Box(Modifier.size(10.dp).background(NapColors.Green, CircleShape))
                }
                HSpace(12)
                Text("${s.quizTotal} / ${s.quizTotal}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Muted)
            }
        }

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Box(Modifier.weight(0.38f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(224.dp)
                        .background(NapColors.Green.copy(alpha = 0.10f), CircleShape)
                        .border(1.dp, NapColors.Green.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = NapColors.Green, modifier = Modifier.size(112.dp))
                }
            }
            Column(Modifier.weight(0.62f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "출발해도 좋아요", fontSize = 56.sp, lineHeight = 68.sp,
                    fontWeight = FontWeight.ExtraBold, color = NapColors.White,
                )
                Text(
                    "${s.quizTotal}문제 모두 잘 맞혔어요 · 평균 반응 " +
                        String.format(Locale.KOREA, "%.1f", s.avgReactionSec) + "초",
                    fontSize = 24.sp, color = NapColors.Muted,
                )
                ResultChips(s.quizResults, failColor = NapColors.Orange, bordered = true)
            }
        }

        ActionButton(
            "출발하기", Icons.Filled.DirectionsCar, NapColors.Blue, vm::depart, Modifier.fillMaxWidth(),
            fontSize = 26.sp,
        )
    }
}

// ═════════════ 공통 컴포넌트 ═════════════

@Composable
private fun NapHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Bedtime, null, tint = NapColors.Outline, modifier = Modifier.size(32.dp))
        HSpace(8)
        Text("파워냅 · 정차 중", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = NapColors.Outline)
    }
}

@Composable
private fun RestartHeader(title: String = "재출발 확인", trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun StatusChip(text: String, shape: Shape = CircleShape, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(shape).background(NapColors.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            color = if (onClick != null) NapColors.Blue else NapColors.Muted,
        )
    }
}

@Composable
private fun TimerGauge(
    diameter: Dp,
    stroke: Dp,
    progress: Float?,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.size(diameter).clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val topLeft = Offset(sw, sw)
            val arcSize = Size(size.width - sw * 2, size.height - sw * 2)
            drawArc(NapColors.Card, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            if (progress != null && progress > 0f) {
                drawArc(
                    NapColors.Blue, -90f, 360f * progress.coerceAtMost(1f), false, topLeft, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun BigClock(text: String) =
    Text(text, fontSize = 112.sp, fontWeight = FontWeight.Bold, color = NapColors.TimerText, letterSpacing = (-2).sp)

@Composable
private fun AppliedStatusLine(settings: NapSettings) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatusItem(Icons.Filled.AirlineSeatReclineExtra, "시트 눕힘")
            StatusItem(Icons.Filled.Lightbulb, "조명 ${settings.lightPercent}%")
            StatusItem(Icons.Filled.Thermostat, "온도 ${settings.temperature}℃")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = NapColors.Green, modifier = Modifier.size(24.dp))
            HSpace(8)
            Text("파워냅 세팅 전환 완료", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NapColors.Green)
        }
    }
}

@Composable
private fun StatusItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = NapColors.White, modifier = Modifier.size(28.dp))
        HSpace(8)
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = NapColors.White)
    }
}

@Composable
private fun PresetButtons(
    selected: Int,
    onSelect: (Int) -> Unit,
    height: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NapRules.PRESETS.forEach { minutes ->
            ActionButton(
                text = "${minutes}분",
                icon = null,
                // 1분 단위로 직접 조절해 프리셋과 다른 값이면 둘 다 선택 해제 상태로 보입니다.
                container = if (selected == minutes) NapColors.Blue else NapColors.Button,
                onClick = { onSelect(minutes) },
                modifier = Modifier.weight(1f),
                height = height, radius = radius, fontSize = 32.sp,
            )
        }
    }
}

@Composable
private fun ResultChips(results: List<Boolean?>, failColor: Color, bordered: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        results.forEachIndexed { i, correct ->
            Row(
                Modifier
                    .then(if (bordered) Modifier.border(1.dp, NapColors.Border, CircleShape) else Modifier)
                    .background(NapColors.Card, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (correct == true) Icons.Filled.Check else Icons.Filled.Close, null,
                    tint = if (correct == true) NapColors.Green else failColor,
                    modifier = Modifier.size(24.dp),
                )
                HSpace(8)
                Text("${i + 1}번", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NapColors.White)
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector?,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp,
    radius: Dp = 24.dp,
    fontSize: TextUnit = 28.sp,
    content: Color = NapColors.White,
    weight: FontWeight = FontWeight.Bold,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(radius),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.4f),
            disabledContentColor = content.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(fontSize.value.dp + 4.dp))
            HSpace(12)
        }
        Text(text, fontSize = fontSize, fontWeight = weight, maxLines = 1)
    }
}

@Composable
private fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
private fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))

private fun clock(totalSec: Int) = String.format(Locale.KOREA, "%02d:%02d", totalSec / 60, totalSec % 60)

private fun shortClock(totalSec: Int) = String.format(Locale.KOREA, "%d:%02d", totalSec / 60, totalSec % 60)
