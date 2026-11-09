package com.hyundai.motor_rookie.intervention

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactPhone
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────
// 비상 화면들
//  - EmergencyScreen : Stitch "_7" (응답이 없어 비상등을 켰어요) 수치 그대로
//      + 질문 막대(신고할까요?), 112/119 고르기, 전화 연결 중 상태 (같은 스타일로 추가)
//  - FuelLowScreen   : Stitch "1280_888_7" (연료가 부족해요) 수치 그대로
//  - ManualGuideScreen : "지금 어떻게 해야 해?" 상황 고르기 → 행동요령 (곁 기본 스타일)
// 모두 1280×888 기준으로 만들고 GyeolScaled 로 창 크기에 맞춘다.
// ─────────────────────────────────────────────

private object EmColors {
    val Red = Color(0xFF920000)          // emergency-red
    val Card = Color(0x3D000000)         // rgba(0,0,0,0.24)
    val White = Color(0xFFFFFFFF)
    val Dark = Color(0xFF131417)         // action-dark
}

private object FuelColors {
    val Surface = Color(0xFF121316)
    val Container = Color(0xFF1F1F23)
    val Highest = Color(0xFF343538)
    val OnSurface = Color(0xFFE3E2E6)
    val Outline = Color(0xFF8B91A0)
    val OutlineVariant = Color(0xFF414754)
    val Orange = Color(0xFFEC6A15)       // tertiary-container
    val Peach = Color(0xFFFFB692)        // tertiary
    val Primary = Color(0xFFAAC7FF)
    val Blue = Color(0xFF4090FF)         // primary-container
}

/** MainActivity 에서 맨 위에 한 줄로 올린다. 비상 > 매뉴얼 > 연료 순으로 하나만 보인다 */
@Composable
fun EmergencyLayer(ec: EmergencyController) {
    val ui by ec.ui.collectAsState()
    val manual by ec.manual.collectAsState()
    val fuel by ec.fuel.collectAsState()
    when {
        ui.active -> EmergencyScreen(ui, ec)
        manual.visible -> ManualGuideScreen(manual, ec)
        fuel.visible -> FuelLowScreen(fuel, ec)
    }
}

// ───────────────── 비상 화면 ─────────────────

@Composable
private fun EmergencyScreen(ui: EmergencyUi, ec: EmergencyController) {
    // Surface: 아래 화면으로 터치가 새지 않게
    Surface(Modifier.fillMaxSize(), color = EmColors.Red) {
        GyeolScaled(Modifier.safeDrawingPadding(), baseWidth = 1280f, baseHeight = 888f) {
            Column(
                Modifier.fillMaxSize().padding(gdp(48)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 헤더: 경고 삼각형 124×110 + 60px ExtraBold
                Icon(Icons.Outlined.WarningAmber, null, tint = EmColors.White,
                    modifier = Modifier.size(gdp(124)))
                Spacer(Modifier.height(gdp(8)))
                Text(ui.trigger.title, color = EmColors.White, fontSize = gsp(60), fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.025).em, lineHeight = gsp(60), textAlign = TextAlign.Center)

                // 질문 막대 (음성으로 묻는 중)
                if (ui.question != null) {
                    Spacer(Modifier.height(gdp(28)))
                    QuestionBar(ui, ec)
                    Spacer(Modifier.height(gdp(28)))
                } else {
                    Spacer(Modifier.height(gdp(48)))
                }

                // 본문: 5 : 7, gap 40
                Row(
                    Modifier.fillMaxWidth().widthIn(max = gdp(1560)).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gdp(40))
                ) {
                    ChecklistCard(ui, Modifier.weight(5f).fillMaxHeight())
                    Box(Modifier.weight(7f)) {
                        when (ui.phase) {
                            EmergencyPhase.GUIDE -> ActionGrid(ui, ec)
                            EmergencyPhase.CHOOSE_REPORT -> ReportChoiceGrid(ec)
                            EmergencyPhase.CALLING -> CallingCard(ui, ec)
                        }
                    }
                }

                ui.message?.let {
                    Spacer(Modifier.height(gdp(20)))
                    Text(it, color = EmColors.White.copy(alpha = 0.85f), fontSize = gsp(22))
                }
            }
        }
    }
}

/** 왼쪽: 비상 대응 행동 요령 (rounded 32, p-10, border white/10) */
@Composable
private fun ChecklistCard(ui: EmergencyUi, modifier: Modifier) {
    Column(
        modifier
            .shadow(gdp(24), RoundedCornerShape(gdp(32)))
            .background(EmColors.Card, RoundedCornerShape(gdp(32)))
            .border(1.dp(), EmColors.White.copy(alpha = 0.1f), RoundedCornerShape(gdp(32)))
            .padding(gdp(40))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(10))) {
            Icon(Icons.Outlined.ListAlt, null, tint = EmColors.White.copy(alpha = 0.8f), modifier = Modifier.size(gdp(26)))
            Text("비상 대응 행동 요령", color = EmColors.White.copy(alpha = 0.8f), fontSize = gsp(22),
                fontWeight = FontWeight.Bold, letterSpacing = 0.025.em)
        }
        Spacer(Modifier.height(gdp(24)))
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(gdp(20))) {
            val steps = listOf("① 비상등 켜기", "② 트렁크 열기", "③ 가드레일 밖으로 대피", "④ 신고하기")
            steps.forEachIndexed { i, label ->
                val n = i + 1
                val done = n < ui.step || (n == 1 && ui.hazardOn)
                val current = n == ui.step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(20))) {
                    if (done) {
                        Box(Modifier.size(gdp(48)).shadow(gdp(4), CircleShape).background(EmColors.White, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, tint = EmColors.Red, modifier = Modifier.size(gdp(32)))
                        }
                    } else {
                        Box(Modifier.size(gdp(48)).border(2.dp(), EmColors.White.copy(alpha = 0.7f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text("$n", color = EmColors.White, fontSize = gsp(22), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(label, color = if (done || current) EmColors.White else EmColors.White.copy(alpha = 0.95f),
                        fontSize = gsp(30), fontWeight = if (done || current) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = (-0.025).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (n == 1 && ui.hazardOn) {
                        Spacer(Modifier.weight(1f))
                        Text("작동중", color = EmColors.White, fontSize = gsp(18), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(EmColors.White.copy(alpha = 0.2f), CircleShape)
                                .padding(horizontal = gdp(14), vertical = gdp(4)))
                    } else if (current && n > 1) {
                        Spacer(Modifier.weight(1f))
                        Text("안내 중", color = EmColors.White, fontSize = gsp(18), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(EmColors.White.copy(alpha = 0.2f), CircleShape)
                                .padding(horizontal = gdp(14), vertical = gdp(4)))
                    }
                }
            }
        }
    }
}

/** 오른쪽 2×2 큰 버튼 (h-120, rounded 32, gap 24) */
@Composable
private fun ActionGrid(ui: EmergencyUi, ec: EmergencyController) {
    Column(verticalArrangement = Arrangement.spacedBy(gdp(24))) {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(24))) {
            WhiteAction("112 / 119 신고", null, 30, Modifier.weight(1f), asterisk = true) { ec.onReportButton() }
            WhiteAction("긴급견인 1588-2504", Icons.Outlined.LocalShipping, 27, Modifier.weight(1f)) {
                ec.onCallButton(CallTarget.TOW)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(24))) {
            WhiteAction(ui.contactName?.let { "${it}에게 전화" } ?: "비상연락처에 전화",
                Icons.Outlined.ContactPhone, 28, Modifier.weight(1f)) { ec.onContactButton() }
            OutlineAction("괜찮아요, 취소", Icons.Outlined.CheckCircle, Modifier.weight(1f)) { ec.cancel() }
        }
    }
}

/** [112 / 119 신고]를 누르면: 경찰 / 구급·화재 고르기 */
@Composable
private fun ReportChoiceGrid(ec: EmergencyController) {
    Column(verticalArrangement = Arrangement.spacedBy(gdp(24))) {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(24))) {
            WhiteAction("112 경찰 · 사고", null, 30, Modifier.weight(1f), asterisk = true) { ec.onCallButton(CallTarget.POLICE) }
            WhiteAction("119 구급 · 화재", null, 30, Modifier.weight(1f), asterisk = true) { ec.onCallButton(CallTarget.FIRE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(24))) {
            WhiteAction("긴급견인 1588-2504", Icons.Outlined.LocalShipping, 27, Modifier.weight(1f)) {
                ec.onCallButton(CallTarget.TOW)
            }
            OutlineAction("뒤로", Icons.Outlined.ArrowBack, Modifier.weight(1f)) { ec.backToGuide() }
        }
    }
}

/** 전화 연결 중 (2×2 자리를 한 장으로) */
@Composable
private fun CallingCard(ui: EmergencyUi, ec: EmergencyController) {
    val t = ui.calling ?: return
    val preset = t == CallTarget.POLICE || t == CallTarget.FIRE || t == CallTarget.TOW
    Column(
        Modifier.fillMaxWidth().height(gdp(264))
            .shadow(gdp(16), RoundedCornerShape(gdp(32)))
            .background(EmColors.White, RoundedCornerShape(gdp(32)))
            .padding(horizontal = gdp(40), vertical = gdp(28)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
            Icon(Icons.Filled.Call, null, tint = EmColors.Dark, modifier = Modifier.size(gdp(42)))
            Text(if (preset) "${t.number} ${t.label}" else t.label, color = EmColors.Dark, fontSize = gsp(34),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.025).em, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(gdp(8)))
        Text(
            when {
                ui.callSimulated -> "연결하고 있어요 · 시뮬레이션 (에뮬레이터에는 전화 기능이 없어요)"
                else -> "전화 앱으로 연결했어요"
            },
            color = Color(0xFF5F6168), fontSize = gsp(22), textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(gdp(20)))
        Button(
            onClick = { ec.endCall() },
            modifier = Modifier.height(gdp(72)).widthIn(min = gdp(280)),
            shape = RoundedCornerShape(gdp(24)),
            colors = ButtonDefaults.buttonColors(containerColor = EmColors.Dark, contentColor = EmColors.White)
        ) {
            Text("통화 끝", fontSize = gsp(26), fontWeight = FontWeight.Bold)
        }
    }
}

/** 질문 막대: 🎙 "신고할까요?" + [응]/[아니요] (시뮬레이션) */
@Composable
private fun QuestionBar(ui: EmergencyUi, ec: EmergencyController) {
    Row(
        Modifier.widthIn(max = gdp(1184))
            .background(EmColors.White.copy(alpha = 0.15f), RoundedCornerShape(gdp(999)))
            .border(1.dp(), EmColors.White.copy(alpha = 0.25f), RoundedCornerShape(gdp(999)))
            .padding(start = gdp(32), end = gdp(16), top = gdp(12), bottom = gdp(12)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gdp(16))
    ) {
        Icon(if (ui.listening) Icons.Outlined.Mic else Icons.Outlined.VolumeUp, null,
            tint = EmColors.White, modifier = Modifier.size(gdp(36)))
        Column(Modifier.weight(1f, fill = false)) {
            Text(ui.question ?: "", color = EmColors.White, fontSize = gsp(32), fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.025).em)
            Text(if (ui.manualAnswer) "버튼으로 대답해 주세요" else if (ui.listening) "듣고 있어요 · '응' 또는 '아니요'" else "곁이 묻고 있어요",
                color = EmColors.White.copy(alpha = 0.8f), fontSize = gsp(20))
        }
        if (ui.manualAnswer) {
            Spacer(Modifier.width(gdp(8)))
            Button(
                onClick = { ec.answer(true) },
                modifier = Modifier.height(gdp(64)).widthIn(min = gdp(120)),
                shape = RoundedCornerShape(gdp(999)),
                colors = ButtonDefaults.buttonColors(containerColor = EmColors.White, contentColor = EmColors.Dark)
            ) { Text("응", fontSize = gsp(26), fontWeight = FontWeight.ExtraBold) }
            OutlinedButton(
                onClick = { ec.answer(false) },
                modifier = Modifier.height(gdp(64)).widthIn(min = gdp(140)),
                shape = RoundedCornerShape(gdp(999)),
                border = BorderStroke(2.dp(), EmColors.White),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmColors.White)
            ) { Text("아니요", fontSize = gsp(26), fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun WhiteAction(
    label: String, icon: ImageVector?, textSize: Int, modifier: Modifier,
    asterisk: Boolean = false, onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(gdp(120)).shadow(gdp(16), RoundedCornerShape(gdp(32))),
        shape = RoundedCornerShape(gdp(32)),
        contentPadding = PaddingValues(horizontal = gdp(24)),
        colors = ButtonDefaults.buttonColors(containerColor = EmColors.White, contentColor = EmColors.Dark)
    ) {
        if (asterisk) Asterisk(gdp(42).value) else if (icon != null) Icon(icon, null, modifier = Modifier.size(gdp(42)))
        Spacer(Modifier.width(gdp(16)))
        Text(label, fontSize = gsp(textSize), fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.025).em,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutlineAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(gdp(120)),
        shape = RoundedCornerShape(gdp(32)),
        border = BorderStroke(3.dp(), EmColors.White),
        contentPadding = PaddingValues(horizontal = gdp(24)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = EmColors.White)
    ) {
        Icon(icon, null, modifier = Modifier.size(gdp(40)))
        Spacer(Modifier.width(gdp(16)))
        Text(label, fontSize = gsp(28), fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.025).em, maxLines = 1)
    }
}

/** Stitch 의 'emergency' 아이콘(굵은 별표) */
@Composable
private fun Asterisk(sizeDp: Float) {
    Canvas(Modifier.size(androidx.compose.ui.unit.Dp(sizeDp))) {
        val r = size.minDimension * 0.42f
        val c = center
        val w = size.minDimension * 0.17f
        for (k in 0 until 3) {
            val a = Math.toRadians(90.0 + k * 60.0)
            val dx = (cos(a) * r).toFloat()
            val dy = (sin(a) * r).toFloat()
            drawLine(EmColors.Dark, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = w, cap = StrokeCap.Butt)
        }
    }
}

@Composable
private fun Int.dp() = gdp(this)

// ───────────────── 연료 부족 (Stitch 1280_888_7) ─────────────────

@Composable
private fun FuelLowScreen(f: FuelUi, ec: EmergencyController) {
    Surface(Modifier.fillMaxSize(), color = FuelColors.Surface) {
        GyeolScaled(Modifier.safeDrawingPadding(), baseWidth = 1280f, baseHeight = 888f) {
            Box(Modifier.fillMaxSize().padding(gdp(32)), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = gdp(1216)).fillMaxWidth().height(gdp(824))) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gdp(40))) {
                        // 왼쪽 40%: 아이콘 · 제목 · 주행 가능 거리
                        Column(Modifier.weight(0.4f).padding(gdp(24)), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(gdp(160)).background(FuelColors.Orange.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.LocalGasStation, null, tint = FuelColors.Orange, modifier = Modifier.size(gdp(80)))
                            }
                            Spacer(Modifier.height(gdp(24)))
                            Text("연료가 부족해요", color = FuelColors.OnSurface, fontSize = gsp(60), fontWeight = FontWeight.Bold,
                                lineHeight = gsp(72), letterSpacing = (-0.025).em, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(gdp(12)))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("주행 가능 거리", color = FuelColors.Outline, fontSize = gsp(26), fontWeight = FontWeight.Medium)
                                Text(" ${f.rangeKm} km", color = FuelColors.Peach, fontSize = gsp(26), fontWeight = FontWeight.Bold)
                            }
                        }
                        // 오른쪽 60%: 주유소 카드
                        Column(
                            Modifier.weight(0.6f)
                                .background(FuelColors.Container, RoundedCornerShape(gdp(24)))
                                .padding(gdp(32))
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.cardTitle, color = FuelColors.OnSurface, fontSize = gsp(40), fontWeight = FontWeight.SemiBold,
                                    lineHeight = gsp(52), modifier = Modifier.weight(1f))
                                Text(f.badge, color = FuelColors.Primary, fontSize = gsp(24), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.background(FuelColors.Highest, CircleShape)
                                        .padding(horizontal = gdp(12), vertical = gdp(4)))
                            }
                            Spacer(Modifier.height(gdp(24)))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
                                Icon(Icons.Filled.LocationOn, null, tint = FuelColors.Blue, modifier = Modifier.size(gdp(36)))
                                Text(f.primary, color = FuelColors.OnSurface, fontSize = gsp(40), fontWeight = FontWeight.SemiBold,
                                    lineHeight = gsp(52), letterSpacing = (-0.025).em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(f.primaryDetail, color = FuelColors.Outline, fontSize = gsp(26), lineHeight = gsp(36),
                                modifier = Modifier.padding(start = gdp(48), top = gdp(8), bottom = gdp(8)))
                            if (f.next != null) {
                                Box(Modifier.padding(vertical = gdp(24)).fillMaxWidth().height(gdp(1)).background(FuelColors.OutlineVariant))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
                                    Icon(Icons.Outlined.Schedule, null, tint = FuelColors.Outline, modifier = Modifier.size(gdp(28)))
                                    Text(f.next, color = FuelColors.Outline, fontSize = gsp(24), fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    // 하단 버튼 h-76, gap 16, mt 24
                    Spacer(Modifier.height(gdp(24)))
                    Row(Modifier.fillMaxWidth().height(gdp(76)), horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                        FuelButton("닫기", Icons.Outlined.Close, FuelColors.Highest, FuelColors.OnSurface) { ec.closeFuel() }
                        FuelButton(if (f.target != null) "안내 시작" else "내비에서 찾기", Icons.Filled.Navigation,
                            FuelColors.Blue, Color.White) { ec.fuelGuide() }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.FuelButton(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        shape = RoundedCornerShape(gdp(24)),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)
    ) {
        Icon(icon, null, modifier = Modifier.size(gdp(32)))
        Spacer(Modifier.width(gdp(12)))
        Text(label, fontSize = gsp(26), fontWeight = FontWeight.Bold)
    }
}

// ───────────────── 매뉴얼 안내 ("지금 어떻게 해야 해?") ─────────────────

private fun ManualTopic.icon(): ImageVector = when (this) {
    ManualTopic.TIRE -> Icons.Outlined.Build
    ManualTopic.WARNING -> Icons.Outlined.ReportProblem
    ManualTopic.BREAKDOWN -> Icons.Outlined.DirectionsCar
    ManualTopic.FIRE -> Icons.Outlined.Whatshot
}

private fun ManualTopic.tint(): Color = when (this) {
    ManualTopic.FIRE -> GyeolColors.Red
    ManualTopic.WARNING -> GyeolColors.Yellow
    else -> GyeolColors.Orange
}

@Composable
private fun ManualGuideScreen(m: ManualUi, ec: EmergencyController) {
    Surface(Modifier.fillMaxSize(), color = GyeolColors.Bg) {
        GyeolScaled(Modifier.safeDrawingPadding(), baseWidth = 1280f, baseHeight = 888f) {
            Box(Modifier.fillMaxSize().padding(gdp(32)), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = gdp(1216)).fillMaxWidth().height(gdp(824))) {
                    val topic = m.topic
                    if (topic == null) TopicChooser(ec, Modifier.weight(1f)) else TopicGuide(topic, m.step, Modifier.weight(1f))
                    Spacer(Modifier.height(gdp(24)))
                    Row(Modifier.fillMaxWidth().height(gdp(76)), horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                        if (topic != null) {
                            FuelButton("다른 상황", Icons.Outlined.ArrowBack, FuelColors.Highest, FuelColors.OnSurface) { ec.manualBackToTopics() }
                            FuelButton("다시 듣기", Icons.Outlined.Refresh, FuelColors.Highest, FuelColors.OnSurface) { ec.readManual(topic) }
                        }
                        FuelButton("닫기", Icons.Outlined.Close, if (topic == null) FuelColors.Highest else GyeolColors.Blue,
                            Color.White) { ec.closeManual() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicChooser(ec: EmergencyController, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Text("지금 어떤 상황인가요?", color = GyeolColors.Text, fontSize = gsp(60), fontWeight = FontWeight.Bold,
            letterSpacing = (-0.025).em, lineHeight = gsp(72))
        Spacer(Modifier.height(gdp(12)))
        Text("고르면 행동요령을 읽어 드려요 · \"타이어 펑크 났어\"처럼 말해도 돼요",
            color = GyeolColors.Sub, fontSize = gsp(26))
        Spacer(Modifier.height(gdp(40)))
        val topics = ManualTopic.values().toList()
        topics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gdp(24))) {
                row.forEach { t ->
                    Button(
                        onClick = { ec.readManual(t) },
                        modifier = Modifier.weight(1f).height(gdp(140)),
                        shape = RoundedCornerShape(gdp(32)),
                        contentPadding = PaddingValues(horizontal = gdp(32)),
                        colors = ButtonDefaults.buttonColors(containerColor = GyeolColors.Raised, contentColor = GyeolColors.Text)
                    ) {
                        Box(Modifier.size(gdp(72)).background(t.tint().copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(t.icon(), null, tint = t.tint(), modifier = Modifier.size(gdp(40)))
                        }
                        Spacer(Modifier.width(gdp(20)))
                        Text(t.label, fontSize = gsp(32), fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(gdp(24)))
        }
    }
}

@Composable
private fun TopicGuide(topic: ManualTopic, step: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gdp(40))) {
        Column(Modifier.weight(0.4f).padding(gdp(24)), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(gdp(160)).background(topic.tint().copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(topic.icon(), null, tint = topic.tint(), modifier = Modifier.size(gdp(80)))
            }
            Spacer(Modifier.height(gdp(24)))
            Text(topic.title, color = GyeolColors.Text, fontSize = gsp(52), fontWeight = FontWeight.Bold,
                lineHeight = gsp(64), letterSpacing = (-0.025).em, textAlign = TextAlign.Center)
        }
        Column(
            Modifier.weight(0.6f)
                .background(GyeolColors.Surface, RoundedCornerShape(gdp(24)))
                .padding(gdp(32)),
            verticalArrangement = Arrangement.spacedBy(gdp(24))
        ) {
            topic.steps.forEachIndexed { i, s ->
                val done = i < step
                val current = i == step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(20))) {
                    when {
                        done -> Box(Modifier.size(gdp(48)).background(GyeolColors.Text, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, tint = GyeolColors.Bg, modifier = Modifier.size(gdp(30)))
                        }
                        current -> Box(Modifier.size(gdp(48)).background(GyeolColors.Blue, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = GyeolColors.Text, fontSize = gsp(22), fontWeight = FontWeight.Bold)
                        }
                        else -> Box(Modifier.size(gdp(48)).border(2.dp(), GyeolColors.Divider, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = GyeolColors.Sub, fontSize = gsp(22), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(s, color = if (current || done) GyeolColors.Text else GyeolColors.Sub, fontSize = gsp(28),
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, lineHeight = gsp(38),
                        letterSpacing = (-0.025).em, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
