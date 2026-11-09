package com.hyundai.motor_rookie.intervention

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
// 시연/테스트용 화면
//  - DrivingMainScreen: Stitch "메인 주행" 디자인 (PPT 2번). 메인 담당 팀원 화면이 생기면 교체
//  - SimPanel: 시뮬레이션 패널 (설정 버튼으로 열고 닫기)
//  - 그 위에 InterventionOverlay(퀴즈 + 배너)
// ─────────────────────────────────────────────

@Composable
fun InterventionDemoScreen(
    manager: InterventionManager,
    settings: InterventionSettings,
    onSettingsChange: (InterventionSettings) -> Unit,
    detection: DetectionLink? = null,
    emergency: EmergencyController? = null
) {
    val ui by manager.ui.collectAsState()
    // 감지 모듈과 연결돼 있으면 점수는 감지 모듈이 넣는다 (슬라이더는 꺼 둠)
    val live = detection?.takeIf { it.useDetection }?.live
    val feed: (Int) -> Unit = detection?.feedScore ?: { manager.onFatigueScore(it) }
    var score by remember { mutableFloatStateOf(ui.score.toFloat()) }
    var panelOpen by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }
    var extraMin by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current

    // 퀴즈 오답 점수를 시뮬레이션 점수에 반영 (실제로는 감지 파트가 반영)
    LaunchedEffect(Unit) {
        manager.events.collect { e ->
            when (e) {
                is InterventionEvent.QuizFinished -> {
                    // 감지 모듈 연결 중이면 MainActivity 가 퀴즈 결과를 감지 엔진(s_talk)에 넘긴다
                    if (detection?.useDetection != true) {
                        score = (score + e.penaltyPoints).coerceAtMost(100f)
                        feed(score.roundToInt())
                    }
                }
                InterventionEvent.Emergency -> {}   // 비상 화면은 MainActivity 의 EmergencyLayer 가 띄움
                is InterventionEvent.Snoozed -> {}
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(10_000) } }

    val drivingMin = live?.drivingMinutes ?: (((nowMs - startedAt) / 60_000).toInt() + extraMin)

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        GyeolScaled(Modifier.background(GyeolColors.Bg)) {
            DrivingMainScreen(
                ui = ui,
                drivingMinutes = drivingMin,
                onTalk = { manager.talkNow() },
                onFindRest = { manager.findRestNow() },
                onSettings = { panelOpen = !panelOpen }
            )

            if (panelOpen) {
                SimPanel(
                    manager = manager,
                    ui = ui,
                    score = score,
                    onScore = { score = it; feed(it.roundToInt()) },
                    detection = detection,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onAddMinutes = { extraMin = (extraMin + it).coerceAtLeast(0) },
                    onClose = { panelOpen = false },
                    emergency = emergency,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        InterventionOverlay(manager)
    }
}

// ───────────────── 메인 주행 화면 (Stitch 2_2 · 1_48) ─────────────────
// 위: 배너 자리 140px · 가운데: 게이지 440px + gap-20 + 상태 글자 · 아래: 버튼 3개 (gap-6, max 1500)

@Composable
fun DrivingMainScreen(
    ui: InterventionUi,
    drivingMinutes: Int,
    onTalk: () -> Unit,
    onFindRest: () -> Unit,
    onSettings: () -> Unit
) {
    val color = stageColor(ui.stage, ui.score)
    Column(Modifier.fillMaxSize().padding(gdp(32))) {
        Spacer(Modifier.height(gdp(140)))   // 배너 자리 (배너는 InterventionOverlay가 그림)

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            FatigueGauge(ui.score)
            Spacer(Modifier.width(gdp(80)))
            if (ui.restCard.visible && !ui.quiz.visible) {
                Spacer(Modifier.width(gdp(580)))     // 쉼터 카드 자리 (카드는 InterventionOverlay가 그림)
            } else Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(gdp(16)).background(color, CircleShape))
                    Spacer(Modifier.width(gdp(12)))
                    Text(stageLabel(ui.stage, ui.score), color = color, fontSize = gsp(40),
                        fontWeight = FontWeight.Bold, letterSpacing = Tight)
                }
                Spacer(Modifier.height(gdp(16)))
                Text(ui.status, color = GyeolColors.Text, fontSize = gsp(30), fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(gdp(20)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = GyeolColors.SubMain, modifier = Modifier.size(gdp(30)))
                    Spacer(Modifier.width(gdp(12)))
                    Text(drivingText(drivingMinutes), color = GyeolColors.SubMain, fontSize = gsp(24),
                        fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(gdp(40)))
                StageLegend(ui.score)
            }
        }

        Row(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = gdp(1500)).fillMaxWidth()
                .padding(bottom = gdp(8)),
            horizontalArrangement = Arrangement.spacedBy(gdp(24))
        ) {
            GyeolButton("말 걸어줘", Icons.Outlined.Mic, Modifier.weight(1f), container = GyeolColors.Blue, onClick = onTalk)
            GyeolButton("쉼터 찾기", Icons.Outlined.LocalCafe, Modifier.weight(1f), container = GyeolColors.ButtonDark, onClick = onFindRest)
            GyeolButton("설정", Icons.Outlined.Settings, Modifier.weight(1f), container = GyeolColors.ButtonDark, onClick = onSettings)
        }
    }
}

private fun drivingText(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}시간 ${m}분째 운전 중" else "${m}분째 운전 중"
}

// ───────────────── 시뮬레이션 패널 ─────────────────

@Composable
private fun SimPanel(
    manager: InterventionManager,
    ui: InterventionUi,
    score: Float,
    onScore: (Float) -> Unit,
    settings: InterventionSettings,
    onSettingsChange: (InterventionSettings) -> Unit,
    onAddMinutes: (Int) -> Unit,
    onClose: () -> Unit,
    detection: DetectionLink? = null,
    emergency: EmergencyController? = null,
    modifier: Modifier = Modifier
) {
    val fromDetection = detection?.useDetection == true
    var simTemp by remember { mutableStateOf(manager.simClimate.current) }
    var simWindow by remember { mutableStateOf(manager.simWindow.summary()) }
    var simWaypoint by remember { mutableStateOf<String?>(null) }
    var naviConnected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            simTemp = manager.simClimate.current
            simWindow = manager.simWindow.summary()
            simWaypoint = manager.simNavigation.lastWaypoint?.displayName
            naviConnected = manager.realNaviConnected
            delay(500)
        }
    }
    val scoreColor = GyeolColors.forScore(if (fromDetection) ui.score else score.roundToInt())

    val panelScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    Column(
        modifier
            .fillMaxHeight()
            .width(gdp(640))
            .background(GyeolColors.Surface, RoundedCornerShape(topStart = gdp(32), bottomStart = gdp(32)))
            // 터치패드 두 손가락 스크롤·마우스 휠도 확실히 먹게 (에뮬레이터용)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val change = e.changes.firstOrNull() ?: continue
                            if (change.isConsumed) continue
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                scrollScope.launch { panelScroll.scrollBy(dy * 120f) }
                                change.consume()
                            }
                        }
                    }
                }
            }
            .padding(gdp(32))
            .verticalScroll(panelScroll),
        verticalArrangement = Arrangement.spacedBy(gdp(20))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시뮬레이션 모드", color = GyeolColors.Text, fontSize = gsp(32), fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(gdp(64))) {
                Icon(Icons.Rounded.Close, "닫기", tint = GyeolColors.Sub, modifier = Modifier.size(gdp(36)))
            }
        }

        // 감지 모듈 연결 (1번 파트)
        if (detection != null) {
            PanelToggle("졸음 지수를 감지 모듈에서 받기", detection.useDetection, detection.onUseDetectionChange)
            detection.live?.let { s ->
                Text(
                    "감지: ${s.score}점 · 운전 ${s.drivingMinutes}분 · 기준선 ${if (s.baselineReady) "완료" else "학습 중"}" +
                            if (s.reasons.isNotEmpty()) " · ${s.reasons.joinToString(" · ")}" else "",
                    color = GyeolColors.Sub, fontSize = gsp(22)
                )
                Text(
                    "신호  시간 ${"%.2f".format(s.signals.time)} · 핸들 ${"%.2f".format(s.signals.steer)} · " +
                            "속도 ${"%.2f".format(s.signals.speed)} · 대화 ${"%.2f".format(s.signals.talk)}",
                    color = GyeolColors.Sub, fontSize = gsp(22)
                )
            }
            // 테스트 모드: 운전 상태를 골라서 감지 점수를 움직인다 (기어는 Virtual Console 을 따름)
            val lv = detection.scenarioLevel
            val setLv = detection.onScenarioLevel
            if (lv != null && setLv != null) {
                Text("운전 상태 (테스트 모드 · D단에서 적용)", color = GyeolColors.Text, fontSize = gsp(24),
                    fontWeight = FontWeight.Bold)
                // 패널이 좁아서 한 줄에 4개면 글자가 잘림 → 2개씩 두 줄
                listOf("정상", "조금 졸림", "많이 졸림", "아주 졸림").withIndex().chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
                        row.forEach { (i, name) ->
                            GyeolButton(name, null, Modifier.weight(1f),
                                container = if (i == lv) GyeolColors.Blue else GyeolColors.Raised,
                                medium = true) { setLv(i) }
                        }
                    }
                }
                Text("정상 → 괜찮음 유지 · 조금 졸림 → 대화 · 많이 졸림 → 환경 · 아주 졸림 → 휴식 (1~2분 걸려요)",
                    color = GyeolColors.Sub, fontSize = gsp(18))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
                GyeolButton("감지 처음부터 다시", null, medium = true) { detection.onRestart() }
                detection.onOpenPowerNap?.let { open -> GyeolButton("파워냅 설정", null, medium = true) { open() } }
            }
        }

        // 졸음 지수
        val shown = if (fromDetection) ui.score.toFloat() else score
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (fromDetection) "졸음 지수 (감지 모듈) " else "졸음 지수 ", color = GyeolColors.Sub, fontSize = gsp(24))
            Text("${shown.roundToInt()}", color = scoreColor, fontSize = gsp(48), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = shown, onValueChange = onScore, valueRange = 0f..100f, enabled = !fromDetection,
            colors = SliderDefaults.colors(thumbColor = scoreColor, activeTrackColor = scoreColor,
                inactiveTrackColor = GyeolColors.Raised)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
            listOf(22, 48, 68, 78).forEach { v ->
                GyeolButton("$v", null, Modifier.weight(1f), medium = true) { if (!fromDetection) onScore(v.toFloat()) }
            }
        }

        // 주행 시간
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
            Text("주행 시간", color = GyeolColors.Text, fontSize = gsp(24), modifier = Modifier.weight(1f))
            GyeolButton("−30분", null, medium = true) { onAddMinutes(-30) }
            GyeolButton("+30분", null, medium = true) { onAddMinutes(30) }
        }

        PanelToggle("버튼으로 대답 · 가짜 공조", settings.simulation) { onSettingsChange(settings.copy(simulation = it)) }
        PanelToggle("공조 자동 조절", settings.climateAuto) { onSettingsChange(settings.copy(climateAuto = it)) }
        PanelToggle("창문 자동 열기", settings.windowAuto) { onSettingsChange(settings.copy(windowAuto = it)) }
        PanelToggle("비 오는 중 (창문 안 열기)", settings.simRaining) { onSettingsChange(settings.copy(simRaining = it)) }
        PanelToggle("실제 Pleos 내비로 안내", settings.useRealNavi) { onSettingsChange(settings.copy(useRealNavi = it)) }
        if (settings.useRealNavi) {
            PanelToggle("길안내가 끝나면 곁으로 돌아오기", settings.naviAutoReturn) {
                onSettingsChange(settings.copy(naviAutoReturn = it))
            }
        }

        // 비상 상황 (시뮬레이션)
        if (emergency != null) {
            Text("비상 상황 시뮬레이션", color = GyeolColors.Text, fontSize = gsp(24), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
                GyeolButton("무응답 3회", null, Modifier.weight(1f), medium = true) {
                    emergency.start(EmergencyTrigger.NO_RESPONSE)
                }
                GyeolButton("급정거", null, Modifier.weight(1f), medium = true) {
                    emergency.start(EmergencyTrigger.HARD_BRAKE)
                }
            }
            GyeolButton("연료 부족", null, Modifier.fillMaxWidth(), medium = true) { emergency.fuelLow(28, force = true) }
            GyeolButton("지금 어떻게 해야 해?", null, Modifier.fillMaxWidth(), medium = true) { emergency.openManual() }
        }

        // 개입 민감도
        Text("개입 민감도", color = GyeolColors.Text, fontSize = gsp(24))
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(12))) {
            Sensitivity.values().forEach { sv ->
                GyeolButton(sv.label, null, Modifier.weight(1f), container = if (settings.sensitivity == sv) GyeolColors.Blue else GyeolColors.Raised, medium = true) {
                    onSettingsChange(settings.copy(sensitivity = sv))
                }
            }
        }

        if (settings.simulation) {
            Text("가짜 공조: ${simTemp.temperature}℃ · 바람 ${simTemp.fanSpeed}단계",
                color = GyeolColors.Sub, fontSize = gsp(22))
            Text("가짜 창문: $simWindow", color = GyeolColors.Sub, fontSize = gsp(22))
            Text("가짜 내비 경유지: ${simWaypoint ?: "없음"}", color = GyeolColors.Sub, fontSize = gsp(22))
        }
        Text(if (naviConnected) "실제 Pleos 내비: 연결됨 (안내 시작 → 실제 내비로 전송)" else "실제 Pleos 내비: [안내 시작]을 누르면 연결해요",
            color = if (naviConnected) GyeolColors.Green else GyeolColors.Sub, fontSize = gsp(22))
        Text("s_talk = ${"%.2f".format(ui.talkSignal)}", color = GyeolColors.Sub, fontSize = gsp(22))

        Text("기록", color = GyeolColors.Text, fontSize = gsp(24), fontWeight = FontWeight.Bold)
        ui.log.take(12).forEach { Text(it, color = GyeolColors.Sub, fontSize = gsp(18)) }
        // 패널 아래쪽이 Pleos 독(공조 바)에 가려져서, 맨 아래까지 올려 볼 수 있게 여유 공간
        Spacer(Modifier.height(gdp(240)))
    }
}

@Composable
private fun PanelToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = GyeolColors.Text, fontSize = gsp(24), modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = GyeolColors.Blue, uncheckedTrackColor = GyeolColors.Raised)
        )
    }
}