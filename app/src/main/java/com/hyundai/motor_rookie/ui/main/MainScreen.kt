package com.hyundai.motor_rookie.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyundai.motor_rookie.fatigue.FatigueLevel
import com.hyundai.motor_rookie.fatigue.FatigueState
import com.hyundai.motor_rookie.ui.theme.GeotColors

/** 단계별로 바뀌는 글자 */
private data class LevelText(val tag: String, val headline: String, val legend: String)

private fun levelText(level: FatigueLevel): LevelText = when (level) {
    FatigueLevel.NORMAL -> LevelText("괜찮아요", "지금은 괜찮아요", "괜찮음")
    FatigueLevel.TALK -> LevelText("주의", "조금 피곤해 보여요", "대화")
    FatigueLevel.ENVIRONMENT -> LevelText("피곤해요", "잠깐 환기할게요", "환경")
    FatigueLevel.REST -> LevelText("휴식 필요", "쉬어 가요", "휴식")
}

/** "1시간 42분째 운전 중" */
private fun drivingText(minutes: Int): String = when {
    minutes <= 0 -> "방금 출발했어요"
    minutes < 60 -> "${minutes}분째 운전 중"
    minutes % 60 == 0 -> "${minutes / 60}시간째 운전 중"
    else -> "${minutes / 60}시간 ${minutes % 60}분째 운전 중"
}

/**
 * 주행 메인 화면 (Stitch "곁 - 메인 주행").
 *
 * 점수와 단계(state)만 바뀌면 게이지 색, 문구, 단계 칩이 저절로 바뀐다.
 * 22점(초록) → 48점(노랑) → 68점(주황) → 78점(빨강) 화면이 모두 이 화면 하나.
 *
 * @param banner 2·3번 팀원이 위쪽에 띄울 알림 (예: "에어컨 22℃로 낮췄어요"). 없으면 null
 * @param detail 2·3번 팀원이 오른쪽 아래에 넣을 카드 (예: 쉼터 경유지 카드). 없으면 null
 * @param settingsEnabled false면 설정 버튼이 흐려지고 눌리지 않는다 (주행 중 설정 제한)
 */
@Composable
fun MainScreen(
    state: FatigueState,
    onTalk: () -> Unit,
    onFindRest: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsEnabled: Boolean = state.parked,
    banner: (@Composable () -> Unit)? = null,
    detail: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(GeotColors.Background),
    ) {
        // Stitch 화면(1280×888)을 기준으로 지금 화면 크기에 맞춰 전부 같은 비율로 줄이거나 늘린다
        val s = minOf(maxWidth / 1280.dp, maxHeight / 888.dp)
        val color = GeotColors.forLevel(state.level)
        val text = levelText(state.level)

        Column(Modifier.fillMaxSize().padding((32 * s).dp)) {
            TopBar(reasons = state.reasons, s = s)

            if (banner != null) {
                Spacer(Modifier.height((12 * s).dp))
                banner()
            }

            // ── 가운데: 왼쪽 게이지 45% / 오른쪽 상태 55% ──
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = (16 * s).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.45f), contentAlignment = Alignment.Center) {
                    FatigueGauge(score = state.score, color = color, scale = s)
                }
                Column(
                    Modifier
                        .weight(0.55f)
                        .padding(start = (32 * s).dp),
                    verticalArrangement = Arrangement.spacedBy((24 * s).dp),
                ) {
                    // 상태 태그: ● 괜찮아요
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(color, (14 * s).dp)
                        Spacer(Modifier.size((10 * s).dp))
                        Text(text.tag, color = color, fontSize = (24 * s).sp, fontWeight = FontWeight.Bold)
                    }
                    // 큰 문구
                    Text(
                        text.headline,
                        color = GeotColors.TextMain,
                        fontSize = (48 * s).sp,
                        lineHeight = (56 * s).sp,
                        fontWeight = FontWeight.Bold,
                    )
                    // 운전 시간
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = GeotColors.TextSub, modifier = Modifier.size((24 * s).dp))
                        Spacer(Modifier.size((10 * s).dp))
                        Text(drivingText(state.drivingMinutes), color = GeotColors.TextSub, fontSize = (24 * s).sp, fontWeight = FontWeight.Medium)
                    }
                    // 구분선
                    Box(
                        Modifier
                            .widthIn(max = (480 * s).dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(GeotColors.Surface),
                    )
                    // 단계 4개: 지금 단계만 칩으로 강조
                    LevelLegend(current = state.level, s = s)

                    if (detail != null) detail()
                }
            }

            // ── 아래: 큰 버튼 3개 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .height((76 * s).dp),
                horizontalArrangement = Arrangement.spacedBy((16 * s).dp),
            ) {
                BigButton("말 걸어줘", Icons.Outlined.Mic, GeotColors.Primary, true, s, Modifier.weight(1f), onTalk)
                BigButton("쉼터 찾기", Icons.Outlined.LocationOn, GeotColors.Surface, true, s, Modifier.weight(1f), onFindRest)
                BigButton("설정", Icons.Outlined.Settings, GeotColors.Surface, settingsEnabled, s, Modifier.weight(1f), onSettings)
            }
        }
    }
}

/** 맨 위: "곁 PLEOS CONNECT" + 오른쪽에 지금 붙은 가중치 이유 (야간 · 고속 직진) */
@Composable
private fun TopBar(reasons: List<String>, s: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .height((48 * s).dp)
            .padding(horizontal = (8 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("곁", color = GeotColors.TextMain, fontSize = (28 * s).sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size((12 * s).dp))
        Text("PLEOS CONNECT", color = GeotColors.TextSub, fontSize = (18 * s).sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (reasons.isNotEmpty()) {
            Text(reasons.joinToString(" · "), color = GeotColors.TextSub, fontSize = (20 * s).sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** ● 괜찮음  ● 대화  ● 환경  ● 휴식  — 지금 단계는 칩 배경 + 흰 글자 */
@Composable
private fun LevelLegend(current: FatigueLevel, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
        FatigueLevel.entries.forEach { level ->
            val active = level == current
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (active) GeotColors.Chip else Color.Transparent)
                    .padding(horizontal = (14 * s).dp, vertical = (6 * s).dp)
                    .alpha(if (active) 1f else 0.7f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(GeotColors.forLevel(level), (10 * s).dp)
                Spacer(Modifier.size((8 * s).dp))
                Text(
                    levelText(level).legend,
                    color = if (active) GeotColors.TextMain else GeotColors.TextSub,
                    fontSize = (18 * s).sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** 아이콘 + 글자 큰 버튼 (Stitch: 높이 76, 모서리 24) */
@Composable
private fun BigButton(
    label: String,
    icon: ImageVector,
    background: Color,
    enabled: Boolean,
    s: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxSize()
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape((24 * s).dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = GeotColors.TextMain, modifier = Modifier.size((28 * s).dp))
        Spacer(Modifier.size((12 * s).dp))
        Text(label, color = GeotColors.TextMain, fontSize = (24 * s).sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────── 미리보기: Split 화면에서 4단계를 한 번에 확인 ─────────────

private fun sample(score: Int, level: FatigueLevel) = FatigueState(
    score = score,
    level = level,
    drivingMinutes = 102,
    reasons = listOf("야간", "고속 직진"),
)

@Preview(name = "22점 괜찮음", widthDp = 1280, heightDp = 888)
@Composable
private fun PreviewNormal() = MainScreen(sample(22, FatigueLevel.NORMAL), {}, {}, {})

@Preview(name = "48점 대화", widthDp = 1280, heightDp = 888)
@Composable
private fun PreviewTalk() = MainScreen(sample(48, FatigueLevel.TALK), {}, {}, {})

@Preview(name = "68점 환경", widthDp = 1280, heightDp = 888)
@Composable
private fun PreviewEnvironment() = MainScreen(sample(68, FatigueLevel.ENVIRONMENT), {}, {}, {})

@Preview(name = "78점 휴식 + 배너 자리 예시", widthDp = 1280, heightDp = 888)
@Composable
private fun PreviewRest() = MainScreen(
    sample(78, FatigueLevel.REST), {}, {}, {},
    banner = {
        Text(
            "(2·3번 팀원 배너 자리) 창문을 조금 열었어요",
            color = GeotColors.TextMain,
            fontSize = 22.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GeotColors.Chip)
                .padding(16.dp),
        )
    },
)
