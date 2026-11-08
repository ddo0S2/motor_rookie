package com.hyundai.motor_rookie.intervention

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// 앱 시작 흐름: 처음 설정(PPT 1) → 음성 테스트(PPT 1-1) → 주행 메인
// 디자인: Stitch first_time_setup_1280_888 / 1280_888_3 (1280×888 기준 px 그대로)
// ─────────────────────────────────────────────

private enum class AppScreen { SETUP, VOICE_TEST, MAIN }

/** MainActivity의 setContent에서 이것 하나만 부르면 된다 */
@Composable
fun GyeolApp(
    manager: InterventionManager,
    settings: InterventionSettings,
    onSettingsChange: (InterventionSettings) -> Unit,
    detection: DetectionLink? = null,          // 1번 파트(감지 모듈) 연결. null 이면 슬라이더로만 동작
) {
    var screen by remember { mutableStateOf(AppScreen.SETUP) }   // 앱을 켤 때마다 처음 설정부터
    when (screen) {
        AppScreen.SETUP -> SetupScreen(
            onVoiceTest = { screen = AppScreen.VOICE_TEST },
            onDone = {
                screen = AppScreen.MAIN
                detection?.onRestart?.invoke()   // 주행 메인에 들어갈 때 감지 시작 (방금 허용한 권한 반영, CSV도 처음부터)
            },
            onPowerNap = detection?.onOpenPowerNap
        )
        AppScreen.VOICE_TEST -> VoiceTestScreen(manager, onBack = { screen = AppScreen.SETUP })
        AppScreen.MAIN -> InterventionDemoScreen(manager, settings, onSettingsChange, detection)
    }
}

// ───────────────── 비상연락처 저장 (비상 모드 담당도 같이 사용) ─────────────────

data class EmergencyContact(val name: String, val phone: String)

object EmergencyContactStore {
    private const val NAME = "emergency_contact"
    fun load(context: Context): EmergencyContact? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val name = p.getString("name", null) ?: return null
        return EmergencyContact(name, p.getString("phone", "") ?: "")
    }
    fun save(context: Context, c: EmergencyContact) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("name", c.name).putString("phone", c.phone).apply()
    }
}

// ───────────────── 1. 처음 설정 화면 ─────────────────

private val NEEDED_PERMISSIONS = arrayOf(
    "android.car.permission.CAR_SPEED",   // 감지: 속도
    "pleos.car.permission.CAR_DRIVE",     // 감지: 핸들·기어
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private fun permissionsGranted(context: Context) = listOf(
    Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
    "android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_DRIVE"
).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun SetupScreen(onVoiceTest: () -> Unit, onDone: () -> Unit, onPowerNap: (() -> Unit)? = null) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(permissionsGranted(context)) }
    var contact by remember { mutableStateOf(EmergencyContactStore.load(context)) }
    var showContactDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = permissionsGranted(context)
    }

    GyeolScaled(Modifier.background(GyeolColors.Bg).safeDrawingPadding(), baseWidth = 1280f, baseHeight = 888f) {
        Column(Modifier.fillMaxSize().padding(gdp(32))) {
            // 오른쪽 위: Ⓟ 정차 중 설정
            Row(Modifier.fillMaxWidth().height(gdp(32)), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalParking, null, tint = GyeolColors.Sub, modifier = Modifier.size(gdp(24)))
                Spacer(Modifier.width(gdp(8)))
                Text("정차 중 설정", color = GyeolColors.Sub, fontSize = gsp(20), fontWeight = FontWeight.Medium)
            }

            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 왼쪽 38%
                Column(Modifier.weight(0.38f)) {
                    Text("곁", color = GyeolColors.Blue, fontSize = gsp(84), lineHeight = gsp(84),
                        fontWeight = FontWeight.Black, letterSpacing = Tight)
                    Spacer(Modifier.height(gdp(8)))
                    Text("곁과 함께 시작해요", color = GyeolColors.Text, fontSize = gsp(48), lineHeight = gsp(58),
                        fontWeight = FontWeight.Bold, letterSpacing = Tight)
                    Spacer(Modifier.height(gdp(16)))
                    Text(if (onPowerNap != null) "안전한 운전을 위해\n4가지만 준비할게요" else "안전한 운전을 위해\n3가지만 준비할게요", color = GyeolColors.Sub, fontSize = gsp(24),
                        lineHeight = gsp(35))
                }
                Spacer(Modifier.width(gdp(32)))
                // 오른쪽 62%
                Column(Modifier.weight(0.62f), verticalArrangement = Arrangement.spacedBy(gdp(16))) {
                    SetupRow(
                        icon = Icons.Outlined.VerifiedUser, iconTint = GyeolColors.Blue,
                        title = "차량 제어 · 마이크 권한 허용",
                        sub = if (granted) "모든 권한이 허용됐어요" else "공조, 창문, 비상등 제어와 음성 대화에 필요해요",
                        button = if (granted) "허용됨" else "허용",
                        buttonColor = if (granted) GyeolColors.Raised else GyeolColors.Blue,
                        buttonIcon = if (granted) Icons.Outlined.Check else null
                    ) {
                        granted = permissionsGranted(context)
                        if (!granted) launcher.launch(NEEDED_PERMISSIONS)
                    }
                    SetupRow(
                        icon = Icons.Outlined.People, iconTint = GyeolColors.Text,
                        title = "비상연락처 등록",
                        sub = contact?.let { "${it.name} · ${it.phone}" } ?: "비상 시 바로 연락할 사람",
                        button = if (contact != null) "변경" else "연락처 추가",
                        buttonColor = GyeolColors.Raised,
                        buttonIcon = if (contact != null) Icons.Outlined.Check else null
                    ) { showContactDialog = true }
                    SetupRow(
                        icon = Icons.Outlined.Mic, iconTint = GyeolColors.Text,
                        title = "음성 테스트",
                        sub = "“곁아, 들리니?” 라고 말해 보세요",
                        button = "테스트",
                        buttonColor = GyeolColors.Raised,
                        buttonIcon = null,
                        onClick = onVoiceTest
                    )
                    if (onPowerNap != null) {
                        SetupRow(
                            icon = Icons.Outlined.LocalParking, iconTint = GyeolColors.Text,
                            title = "파워냅 환경 저장",
                            sub = "쉼터에서 쉴 때의 시트 · 조명 · 공조",
                            button = "설정",
                            buttonColor = GyeolColors.Raised,
                            buttonIcon = null,
                            onClick = onPowerNap
                        )
                    }
                }
            }

            // 하단 버튼 2개: h-76 rounded-24 26px bold, gap-4
            Row(Modifier.fillMaxWidth().padding(top = gdp(16)), horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                SetupBigButton("나중에 하기", null, GyeolColors.Raised, Modifier.weight(1f), onDone)
                SetupBigButton("다음", Icons.Outlined.ArrowForward, GyeolColors.Blue, Modifier.weight(1f), onDone, iconAfter = true)
            }
        }
    }

    if (showContactDialog) {
        ContactDialog(
            initial = contact,
            onSave = { c -> EmergencyContactStore.save(context, c); contact = c; showContactDialog = false },
            onDismiss = { showContactDialog = false }
        )
    }
}

/** 설정 한 줄: bg #1D1E21 rounded-24 p-6 gap-5 · 아이콘 48 · 제목 28 bold · 설명 20 · 버튼 h-64 rounded-20 24px bold */
@Composable
private fun SetupRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    sub: String,
    button: String,
    buttonColor: Color,
    buttonIcon: ImageVector?,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(GyeolColors.Surface, RoundedCornerShape(gdp(24)))
            .padding(gdp(24)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(gdp(48)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(gdp(40)))
        }
        Spacer(Modifier.width(gdp(20)))
        Column(Modifier.weight(1f)) {
            Text(title, color = GyeolColors.Text, fontSize = gsp(28), lineHeight = gsp(35), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(gdp(4)))
            Text(sub, color = GyeolColors.Sub, fontSize = gsp(20), lineHeight = gsp(26),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(gdp(20)))
        Button(
            onClick = onClick,
            modifier = Modifier.height(gdp(64)),
            shape = RoundedCornerShape(gdp(20)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = gdp(32)),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = GyeolColors.Text)
        ) {
            if (buttonIcon != null) {
                Icon(buttonIcon, null, tint = GyeolColors.Green, modifier = Modifier.size(gdp(26)))
                Spacer(Modifier.width(gdp(8)))
            }
            Text(button, fontSize = gsp(24), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SetupBigButton(
    label: String, icon: ImageVector?, color: Color, modifier: Modifier, onClick: () -> Unit, iconAfter: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(gdp(76)),
        shape = RoundedCornerShape(gdp(24)),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = GyeolColors.Text)
    ) {
        if (icon != null && !iconAfter) { Icon(icon, null, modifier = Modifier.size(gdp(32))); Spacer(Modifier.width(gdp(12))) }
        Text(label, fontSize = gsp(26), fontWeight = FontWeight.Bold)
        if (icon != null && iconAfter) { Spacer(Modifier.width(gdp(12))); Icon(icon, null, modifier = Modifier.size(gdp(32))) }
    }
}

/** 비상연락처 입력 (정차 중에만 쓰는 화면이라 키보드 입력 허용) */
@Composable
private fun ContactDialog(initial: EmergencyContact?, onSave: (EmergencyContact) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = GyeolColors.Text, unfocusedTextColor = GyeolColors.Text,
        focusedBorderColor = GyeolColors.Blue, unfocusedBorderColor = GyeolColors.Divider,
        focusedLabelColor = GyeolColors.Blue, unfocusedLabelColor = GyeolColors.Sub,
        cursorColor = GyeolColors.Blue
    )
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(gdp(560)).background(GyeolColors.Surface, RoundedCornerShape(gdp(32))).padding(gdp(32)),
            verticalArrangement = Arrangement.spacedBy(gdp(20))
        ) {
            Text("비상연락처 등록", color = GyeolColors.Text, fontSize = gsp(32), fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, label = { Text("이름 (예: 엄마)") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("전화번호") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                SetupBigButton("취소", null, GyeolColors.Raised, Modifier.weight(1f), onDismiss)
                SetupBigButton("저장", null, GyeolColors.Blue, Modifier.weight(1f), {
                    if (name.isNotBlank() && phone.isNotBlank()) onSave(EmergencyContact(name.trim(), phone.trim()))
                })
            }
        }
    }
}

// ───────────────── 1-1. 음성 테스트 화면 ─────────────────

private sealed interface TestState {
    data object Idle : TestState
    data object Listening : TestState
    data class Result(val text: String, val simulated: Boolean) : TestState
    data class Failed(val message: String) : TestState
}

@Composable
fun VoiceTestScreen(manager: InterventionManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TestState>(TestState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Stitch 1280_888_3 색
    val panel = Color(0xFF1B1B1F)
    val card = Color(0xFF1F1F23)
    val chip = Color(0xFF343538)
    val micBlue = Color(0xFF4090FF)
    val outline = Color(0xFF8B91A0)
    val ok = Color(0xFF44E359)

    fun start() {
        job?.cancel()
        state = TestState.Listening
        job = scope.launch {
            val heard = manager.runVoiceTest()
            state = when {
                heard is Heard.Text -> TestState.Result(heard.text, simulated = false)
                // 에뮬레이터처럼 음성 인식 엔진이 없을 때: 시뮬레이션 모드면 예시 결과로 시연
                manager.simulation -> { delay(1_500); TestState.Result("오늘 하루도 활기차게", simulated = true) }
                heard is Heard.Error -> TestState.Failed(heard.message)
                else -> TestState.Failed("소리가 들리지 않았어요. 다시 말씀해 주세요")
            }
        }
    }

    GyeolScaled(Modifier.background(GyeolColors.Bg).safeDrawingPadding(), baseWidth = 1280f, baseHeight = 888f) {
        Column(Modifier.fillMaxSize().padding(gdp(32))) {
            // header h-56
            Row(Modifier.fillMaxWidth().height(gdp(56)), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { job?.cancel(); onBack() },
                    modifier = Modifier.size(gdp(56)), shape = RoundedCornerShape(gdp(20)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = chip, contentColor = GyeolColors.Text)
                ) { Icon(Icons.Outlined.ArrowBack, "뒤로 가기", modifier = Modifier.size(gdp(32))) }
                Spacer(Modifier.width(gdp(16)))
                Text("음성 테스트", color = GyeolColors.Text, fontSize = gsp(32), fontWeight = FontWeight.Bold,
                    letterSpacing = Tight, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.LocalParking, null, tint = outline, modifier = Modifier.size(gdp(24)))
                Spacer(Modifier.width(gdp(8)))
                Text("정차 중 설정", color = outline, fontSize = gsp(20))
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = gdp(24)), verticalAlignment = Alignment.CenterVertically) {
                // 왼쪽 40%: 마이크
                Column(
                    Modifier.weight(0.4f).fillMaxHeight().background(panel, RoundedCornerShape(gdp(24))).padding(gdp(32)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    val listening = state is TestState.Listening
                    Box(Modifier.size(gdp(148)).background(if (listening) micBlue else chip, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Mic, null, tint = GyeolColors.Text, modifier = Modifier.size(gdp(56)))
                    }
                    Spacer(Modifier.height(gdp(24)))
                    Text(if (listening) "듣고 있어요" else "시작을 눌러 주세요", color = GyeolColors.Text,
                        fontSize = gsp(32), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(gdp(8)))
                    Text(if (listening) "입력 레벨 감지 중" else "마이크 대기 중", color = outline, fontSize = gsp(20))
                    Spacer(Modifier.height(gdp(16)))
                    Box(Modifier.fillMaxWidth(0.8f).height(gdp(8)).background(chip, CircleShape)) {
                        if (listening) Box(Modifier.fillMaxWidth(0.65f).height(gdp(8)).background(micBlue, CircleShape))
                    }
                }
                Spacer(Modifier.width(gdp(48)))
                // 오른쪽 60%: 안내 + 들은 내용
                Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(gdp(24))) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.VolumeUp, null, tint = GyeolColors.Text, modifier = Modifier.size(gdp(36)))
                            Spacer(Modifier.width(gdp(16)))
                            Text("음성 테스트 중입니다. 말씀해 주세요.", color = GyeolColors.Text, fontSize = gsp(32),
                                fontWeight = FontWeight.Bold, lineHeight = gsp(40))
                        }
                        Spacer(Modifier.height(gdp(8)))
                        Text("예시: “오늘 하루도 활기차게”", color = outline, fontSize = gsp(20),
                            modifier = Modifier.padding(start = gdp(52)))
                    }
                    Column(Modifier.fillMaxWidth().background(card, RoundedCornerShape(gdp(24))).padding(gdp(32))) {
                        Text("들은 내용", color = outline, fontSize = gsp(20))
                        Spacer(Modifier.height(gdp(8)))
                        when (val s = state) {
                            is TestState.Result -> {
                                Text("“${s.text}”", color = GyeolColors.Text, fontSize = gsp(32), fontWeight = FontWeight.Bold,
                                    letterSpacing = Tight)
                                Spacer(Modifier.height(gdp(24)))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = ok, modifier = Modifier.size(gdp(28)))
                                    Spacer(Modifier.width(gdp(8)))
                                    Text(if (s.simulated) "잘 들려요 (시뮬레이션)" else "잘 들려요 (인식 완료)",
                                        color = ok, fontSize = gsp(20), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            is TestState.Failed -> {
                                Text("—", color = outline, fontSize = gsp(32), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(gdp(24)))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.ErrorOutline, null, tint = GyeolColors.Orange, modifier = Modifier.size(gdp(28)))
                                    Spacer(Modifier.width(gdp(8)))
                                    Text(s.message, color = GyeolColors.Orange, fontSize = gsp(20), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            TestState.Listening -> Text("…", color = outline, fontSize = gsp(32), fontWeight = FontWeight.Bold)
                            TestState.Idle -> Text("아직 들은 내용이 없어요", color = outline, fontSize = gsp(26))
                        }
                    }
                }
            }

            // footer h-76: [시작] [확인]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gdp(16))) {
                SetupBigButton("시작", Icons.Outlined.Mic, micBlue, Modifier.weight(1f), { start() })
                SetupBigButton("확인", Icons.Outlined.Check, chip, Modifier.weight(1f), { job?.cancel(); onBack() })
            }
        }
    }
}
