package com.hyundai.motor_rookie.intervention

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────
// 내 파트 화면 — Stitch HTML 그대로
//  - 퀴즈: 1_2(낭독) · 1_1(듣는 중) · 1_4(정답) · 1_3(오답) · 1_5(따라 말하기)
//  - 배너: 2_2(공조 변경 + 되돌리기) · 1280_888_5(복원 완료)
// 사용법: 메인 화면 위에 InterventionOverlay(manager)만 올리면 된다.
// ─────────────────────────────────────────────

/** 메인 화면 위에 덮어 쓰는 레이어: 쉼터 카드 + 퀴즈 화면 + 배너 */
@Composable
fun InterventionOverlay(manager: InterventionManager, modifier: Modifier = Modifier) {
    val ui by manager.ui.collectAsState()
    GyeolScaled(modifier) {
        // 쉼터 카드 (Stitch 3_2): 게이지(440) + 간격(80) + 카드(580)를 가운데 정렬했을 때 카드 자리
        if (ui.restCard.visible && !ui.quiz.visible) {
            val endPad = ((maxWidth - gdp(1100)) / 2).coerceAtLeast(gdp(32))
            Box(
                Modifier.fillMaxSize().padding(top = gdp(172), bottom = gdp(140), end = endPad),
                contentAlignment = Alignment.CenterEnd
            ) {
                RestCard(
                    card = ui.restCard,
                    onGuide = { manager.startRestGuide() },
                    onNext = { manager.nextRest() },
                    onClose = { manager.dismissRest() }
                )
            }
        }
        if (ui.quiz.visible) QuizScreen(ui, manager)
        ui.banner?.let { b ->
            InterventionBanner(
                banner = b,
                onAction = { manager.bannerAction(b.kind) },
                onClose = { manager.dismissBanner() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = gdp(32), start = gdp(32), end = gdp(32))
            )
        }
    }
}

/** 단계 → 색 */
fun stageColor(stage: Stage, score: Int): Color = when {
    stage == Stage.REST -> GyeolColors.Red
    stage == Stage.CLIMATE -> GyeolColors.Orange
    stage == Stage.TALK -> GyeolColors.Yellow
    else -> GyeolColors.Green
}

fun stageLabel(stage: Stage, score: Int): String = stage.label

// ───────────────── 퀴즈 화면 (body: p-8, flex-col justify-between) ─────────────────

@Composable
fun QuizScreen(ui: InterventionUi, manager: InterventionManager) {
    val quiz = ui.quiz
    val divider = GyeolColors.Divider.copy(alpha = 0.4f)
    Column(Modifier.fillMaxSize().background(GyeolColors.Bg).padding(gdp(32))) {

        // header: h-[96px] px-6 border-b border-[#44464E]/40
        Box(
            Modifier.fillMaxWidth().height(gdp(96))
                .drawBehind {
                    drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(horizontal = gdp(24))
        ) {
            StageChip(stageLabel(ui.stage, ui.score), stageColor(ui.stage, ui.score), Modifier.align(Alignment.CenterStart))
            if (quiz.index > 0) {
                Text("${quiz.index} / ${quiz.total}", color = GyeolColors.Sub, fontSize = gsp(28),
                    fontWeight = FontWeight.Bold, letterSpacing = 0.05.em, modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                Text("상태", color = GyeolColors.Sub, fontSize = gsp(28), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(gdp(8)))
                Text("졸음 지수 ${ui.score}", color = GyeolColors.forScore(ui.score), fontSize = gsp(28),
                    fontWeight = FontWeight.Bold, letterSpacing = Tight)
            }
        }

        // main: flex-1 items-center justify-center max-w-[1400px]
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = gdp(1400)).padding(horizontal = gdp(16)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val repeat = quiz.type == QuizType.REPEAT
                Text(quiz.type?.label ?: "곁", color = GyeolColors.Sub, fontSize = gsp(30),
                    fontWeight = FontWeight.Medium, letterSpacing = 0.025.em)
                Spacer(Modifier.height(gdp(16)))                                   // mb-4
                Text(
                    quiz.question,
                    color = GyeolColors.Text,
                    fontSize = if (repeat) gsp(80) else gsp(96),
                    lineHeight = if (repeat) gsp(100) else gsp(115),                 // leading-tight / 1.2
                    fontWeight = FontWeight.Bold,
                    letterSpacing = Tight,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = gdp(1300))
                )
                Spacer(Modifier.height(if (repeat) gdp(32) else gdp(48)))         // mb-8 / mb-12

                when (quiz.phase) {
                    QuizPhase.INTRO, QuizPhase.SPEAKING -> SpeakingBlock(
                        if (quiz.phase == QuizPhase.INTRO) quiz.feedbackSub.ifEmpty { "곁이 말을 걸고 있어요" }
                        else "질문을 읽고 있어요"
                    )
                    QuizPhase.LISTENING -> ListeningBlock(quiz, manager, repeat)
                    QuizPhase.FEEDBACK -> FeedbackBlock(quiz)
                }
            }
        }

        // footer: grid-cols-3 gap-6 max-w-[1600px] pt-2 pb-2
        Row(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = gdp(1600)).fillMaxWidth()
                .padding(vertical = gdp(8)),
            horizontalArrangement = Arrangement.spacedBy(gdp(24))
        ) {
            GyeolButton("괜찮아요", Icons.Rounded.Snooze, Modifier.weight(1f)) { manager.sendCommand(QuizCommand.SNOOZE) }
            GyeolButton("다른 질문", Icons.Rounded.Refresh, Modifier.weight(1f)) { manager.sendCommand(QuizCommand.SKIP) }
            GyeolButton("그만", Icons.Rounded.Close, Modifier.weight(1f)) { manager.sendCommand(QuizCommand.STOP) }
        }
    }
}

/** 1_2: 120px 원 #313236 + volume_up 56px, 30px bold #A4A8AE (gap-5) */
@Composable
private fun SpeakingBlock(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Rounded.VolumeUp, GyeolColors.Text, GyeolColors.Raised, gdp(120), gdp(56))
        Spacer(Modifier.height(gdp(20)))
        Text(text, color = GyeolColors.Sub, fontSize = gsp(30), fontWeight = FontWeight.Bold, letterSpacing = Tight)
    }
}

/**
 * 1_1: 120px 원 #0082FF + mic 56px → (gap-5) 30px bold 흰색 → (gap-5 + mt-1) 600×8 막대
 * 1_5(따라 말하기): 원 mb-6 → 30px medium mb-6 → 막대 바탕 #2B2C31
 */
@Composable
private fun ListeningBlock(quiz: QuizUi, manager: InterventionManager, repeat: Boolean) {
    var remain by remember(quiz.listenStartedAt) { mutableFloatStateOf(1f) }
    LaunchedEffect(quiz.listenStartedAt) {
        while (remain > 0f) {
            val elapsed = System.currentTimeMillis() - quiz.listenStartedAt
            remain = (1f - elapsed.toFloat() / quiz.listenMs).coerceIn(0f, 1f)
            delay(250)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Rounded.Mic, Color.White, GyeolColors.Blue, gdp(120), gdp(56))
        Spacer(Modifier.height(if (repeat) gdp(24) else gdp(20)))
        Text("듣고 있어요 · 말씀해 주세요", color = GyeolColors.Text, fontSize = gsp(30),
            fontWeight = if (repeat) FontWeight.Medium else FontWeight.Bold, letterSpacing = Tight)
        Spacer(Modifier.height(if (repeat) gdp(24) else gdp(24)))
        TimeBar(remain, gdp(600), track = if (repeat) Color(0xFF2B2C31) else GyeolColors.Raised)

        // 시연용: 음성 인식이 안 될 때 버튼으로 대답 (Stitch 화면에는 없음, 시뮬레이션 모드에서만 보임)
        if (quiz.manualInput) {
            Spacer(Modifier.height(gdp(32)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시연용", color = GyeolColors.Sub, fontSize = gsp(22))
                Spacer(Modifier.width(gdp(16)))
                GyeolButton("정답", Icons.Rounded.Check, medium = true) { manager.manualInput.submit(quiz.expected) }
                Spacer(Modifier.width(gdp(12)))
                GyeolButton("오답", Icons.Rounded.Close, medium = true) { manager.manualInput.submit("모르겠어요") }
                Spacer(Modifier.width(gdp(12)))
                GyeolButton("무응답", null, medium = true) { manager.manualInput.submit(null) }
            }
        }
    }
}

/**
 * 1_4 정답: 120px 원 #55F165 + check 72px(#131417) mb-2 → 60px bold → 30px medium #A4A8AE mt-1 (gap-4)
 * 1_3 오답: 120px 원 #FF8A00/20 + info 64px(#FF8A00) mb-2 → 60px bold leading-1.2 → 30px medium mt-1
 */
@Composable
private fun FeedbackBlock(quiz: QuizUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (quiz.feedbackKind) {
            FeedbackKind.CORRECT ->
                IconCircle(Icons.Rounded.Check, GyeolColors.Bg, GyeolColors.Green, gdp(120), gdp(72))
            FeedbackKind.WRONG, FeedbackKind.NO_ANSWER ->
                IconCircle(Icons.Rounded.Info, GyeolColors.Orange, GyeolColors.Orange.copy(alpha = 0.2f), gdp(120), gdp(64))
            else ->
                IconCircle(Icons.Rounded.Info, GyeolColors.Sub, GyeolColors.Raised, gdp(120), gdp(64))
        }
        Spacer(Modifier.height(gdp(24)))                                          // mb-2 + gap-4
        Text(quiz.feedback, color = GyeolColors.Text, fontSize = gsp(60), lineHeight = gsp(72),
            fontWeight = FontWeight.Bold, letterSpacing = Tight, textAlign = TextAlign.Center, maxLines = 2)
        if (quiz.feedbackSub.isNotEmpty()) {
            Spacer(Modifier.height(gdp(20)))                                      // gap-4 + mt-1
            Text(quiz.feedbackSub, color = GyeolColors.Sub, fontSize = gsp(30), fontWeight = FontWeight.Medium,
                letterSpacing = Tight, textAlign = TextAlign.Center)
        }
    }
}

// ───────────────── 개입 알림 배너 (2_2 공조 · 3_1 창문) ─────────────────
// max-w-[1200px] h-[140px] px-8 rounded-[28px] border-[#38393c]/60
// 배경: 공조 #1D1E21 / 창문 #313236 · 아이콘 원 80px + 아이콘 46px · 제목 36px bold · 설명 24px #A0A2AB
// 버튼: 공조 [되돌리기] #313236 + undo / 창문 [닫기] #0082FF + check · [X] 64px 원, 아이콘 34px

@Composable
fun InterventionBanner(banner: Banner, onAction: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val icon: ImageVector
    val color: Color
    val circleAlpha: Float
    when (banner.kind) {
        BannerKind.CLIMATE -> { icon = Icons.Outlined.Air; color = GyeolColors.Orange; circleAlpha = 0.15f }
        BannerKind.WINDOW -> { icon = Icons.Outlined.Air; color = GyeolColors.Red; circleAlpha = 0.2f }
        BannerKind.RESTORED -> { icon = Icons.Rounded.Check; color = GyeolColors.Green; circleAlpha = 0.15f }
        BannerKind.INFO -> { icon = Icons.Rounded.Info; color = GyeolColors.Sub; circleAlpha = 0.15f }
        BannerKind.ERROR -> { icon = Icons.Rounded.Warning; color = GyeolColors.Red; circleAlpha = 0.15f }
    }
    val bg = if (banner.kind == BannerKind.WINDOW) GyeolColors.Raised else GyeolColors.Surface
    val shape = RoundedCornerShape(gdp(28))
    Row(
        modifier
            .widthIn(max = gdp(1200))
            .fillMaxWidth()
            .height(gdp(140))
            .background(bg, shape)
            .border(1.dp, GyeolColors.BannerBorder.copy(alpha = 0.6f), shape)
            .padding(horizontal = gdp(32)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconCircle(icon, color, color.copy(alpha = circleAlpha), gdp(80), gdp(46))
        Spacer(Modifier.width(gdp(24)))
        Column(Modifier.weight(1f)) {
            Text(banner.text, color = GyeolColors.Text, fontSize = gsp(36), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (banner.sub.isNotEmpty()) {
                Spacer(Modifier.height(gdp(4)))
                Text(banner.sub, color = GyeolColors.SubMain, fontSize = gsp(24), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(gdp(24)))
        if (banner.undoable) {
            if (banner.kind == BannerKind.WINDOW) {
                GyeolButton("닫기", Icons.Rounded.Check, container = GyeolColors.Blue, medium = true, onClick = onAction)
            } else {
                GyeolButton("되돌리기", Icons.Outlined.Undo, medium = true, onClick = onAction)
            }
            Spacer(Modifier.width(gdp(16)))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(gdp(64)).background(Color.Transparent, CircleShape)) {
            Icon(Icons.Outlined.Close, contentDescription = "닫기", tint = GyeolColors.SubMain,
                modifier = Modifier.size(gdp(34)))
        }
    }
}

// ───────────────── 쉼터 안내 카드 (3_2) ─────────────────
// w-[580px] bg-[#1D1E21] rounded-[32px] p-8
// 제목 "가까운 쉼터" 40px bold + near_me 36px 파랑
// 아이콘 원 64px #313236 + local_cafe 36px · 이름 40px bold · 거리 30px medium #B0B3BC (mt-2)
// 구분선 #44464E (my-6) · 다음 쉼터 30px semibold #8C8E96 / 24px #6D7078
// 버튼 2개 (gap-4, mt-8): [안내 시작] #0082FF + navigation 36px / [다음 쉼터로] #313236 · h-100 rounded-24

@Composable
fun RestCard(card: RestCardUi, onGuide: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .width(gdp(580))
            .background(GyeolColors.Surface, RoundedCornerShape(gdp(32)))
            .padding(gdp(32))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("가까운 쉼터", color = GyeolColors.Text, fontSize = gsp(40), fontWeight = FontWeight.Bold,
                letterSpacing = Tight, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.NearMe, null, tint = GyeolColors.Blue, modifier = Modifier.size(gdp(36)))
            Spacer(Modifier.width(gdp(8)))
            IconButton(onClick = onClose, modifier = Modifier.size(gdp(48))) {
                Icon(Icons.Outlined.Close, "닫기", tint = GyeolColors.SubMain, modifier = Modifier.size(gdp(32)))
            }
        }
        Spacer(Modifier.height(gdp(24)))

        val cur = card.current
        when {
            card.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = GyeolColors.Blue, strokeWidth = gdp(4), modifier = Modifier.size(gdp(40)))
                Spacer(Modifier.width(gdp(20)))
                Text("쉼터를 찾고 있어요", color = GyeolColors.Sub, fontSize = gsp(30), fontWeight = FontWeight.Medium)
            }
            cur == null -> Text(card.message.ifEmpty { "근처에 쉼터를 찾지 못했어요" },
                color = GyeolColors.Sub, fontSize = gsp(30), fontWeight = FontWeight.Medium)
            else -> {
                Row(verticalAlignment = Alignment.Top) {
                    IconCircle(
                        if (cur.stop.kind == RestKind.SHELTER) Icons.Outlined.LocalParking else Icons.Outlined.LocalCafe,
                        GyeolColors.Text, GyeolColors.Raised, gdp(64), gdp(36)
                    )
                    Spacer(Modifier.width(gdp(16)))
                    Column(Modifier.weight(1f)) {
                        Text(cur.stop.displayName, color = GyeolColors.Text, fontSize = gsp(40), fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(gdp(8)))
                        Text(cur.detail, color = Color(0xFFB0B3BC), fontSize = gsp(30), fontWeight = FontWeight.Medium)
                    }
                }
                card.next?.let { nx ->
                    Spacer(Modifier.height(gdp(24)))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(GyeolColors.Divider))
                    Spacer(Modifier.height(gdp(24)))
                    Text("다음 쉼터 · ${nx.stop.displayName}", color = Color(0xFF8C8E96), fontSize = gsp(30),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(gdp(4)))
                    Text(nx.detail, color = Color(0xFF6D7078), fontSize = gsp(24))
                }
                Spacer(Modifier.height(gdp(32)))
                Row(horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                    CardButton("안내 시작", Icons.Outlined.Navigation, GyeolColors.Blue, Modifier.weight(1f), onGuide)
                    CardButton("다음 쉼터로", null, GyeolColors.Raised, Modifier.weight(1f), onNext)
                }
            }
        }
        if (card.sample) {
            Spacer(Modifier.height(gdp(16)))
            Text("예시 데이터 · 공공데이터 CSV를 넣으면 실제 쉼터로 바뀌어요", color = GyeolColors.Sub.copy(alpha = 0.6f),
                fontSize = gsp(18))
        }
    }
}

/** 카드 버튼: h-[100px] rounded-[24px] 30px bold, 아이콘 36px */
@Composable
private fun CardButton(label: String, icon: ImageVector?, container: Color, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(gdp(100)),
        shape = RoundedCornerShape(gdp(24)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = gdp(12)),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = container, contentColor = GyeolColors.Text)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(gdp(36)))
            Spacer(Modifier.width(gdp(12)))
        }
        Text(label, fontSize = gsp(30), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
