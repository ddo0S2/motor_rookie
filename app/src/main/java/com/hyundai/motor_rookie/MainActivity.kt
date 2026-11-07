package com.hyundai.motor_rookie

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.hyundai.motor_rookie.fatigue.DetectionModule
import com.hyundai.motor_rookie.fatigue.FatigueDebugPanel
import com.hyundai.motor_rookie.fatigue.Sensitivity
import com.hyundai.motor_rookie.ui.main.MainScreen
import com.hyundai.motor_rookie.ui.theme.Motor_rookieTheme
import com.hyundai.motor_rookie.vehicle.VehicleSources

/**
 * 앱 시작 화면.
 * - 기본: Stitch 디자인의 주행 메인 화면 (MainScreen)
 * - [설정] 버튼: 시뮬레이션·민감도 확인 화면 (디버그 패널)
 */
class MainActivity : ComponentActivity() {

    // ───── 여기 3개만 바꿔 가며 확인 ─────
    /** true = CSV 시연 데이터 재생, false = 실제 차량 SDK (PleosVehicleDataSource 의 TODO 를 채운 뒤) */
    private val useSimulation = true
    /** 재생할 데이터: "sim/drowsy_drive.csv"(졸음 운전) 또는 "sim/normal_drive.csv"(정상 운전) */
    private val simFile = "sim/drowsy_drive.csv"
    /** 재생 속도. 60 = 약 2분 30초, 20 = 약 8분 */
    private val speedFactor = 60.0
    // ────────────────────────────────────

    /** 감지 모듈. "처음부터 다시"를 누르면 새로 만든다. */
    private val detection = mutableStateOf<DetectionModule?>(null)

    /** 지금 보이는 화면: false = 주행 메인, true = 확인용(디버그) 화면 */
    private val showDebug = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDetection()

        setContent {
            Motor_rookieTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val d = detection.value
                    if (d == null) {
                        // 감지 모듈 준비 전 (아주 잠깐)
                    } else if (showDebug.value) {
                        DetectionScreen(
                            detection = d,
                            onRestart = { startDetection() },
                            onBack = { showDebug.value = false },
                        )
                    } else {
                        MainScreen(
                            state = d.fatigue.collectAsState().value,
                            onTalk = { Log.d("MAIN", "말 걸어줘 → TODO: 2번 팀원 대화 시작 연결") },
                            onFindRest = { Log.d("MAIN", "쉼터 찾기 → TODO: 3번 팀원 쉼터 안내 연결") },
                            onSettings = { showDebug.value = true },
                            // 원래는 정차 중에만 열려야 하지만(Pleos 주행 규제),
                            // 시연·개발 중에는 시뮬레이션 화면으로 가야 해서 항상 열어 둔다.
                            settingsEnabled = true,
                        )
                    }
                }
            }
        }
    }

    /** 감지 모듈을 (다시) 만들고 시작 */
    private fun startDetection() {
        detection.value?.stop()
        val source = VehicleSources.create(
            context = this,
            scope = lifecycleScope,
            simulation = useSimulation,
            simFile = simFile,
            speedFactor = speedFactor,
        )
        val module = DetectionModule(source, lifecycleScope)
        module.start()
        detection.value = module
    }

    override fun onDestroy() {
        detection.value?.stop()
        super.onDestroy()
    }
}

/**
 * 확인용 화면 (설정 > 시뮬레이션 모드 자리): 디버그 패널 + 테스트 버튼.
 */
@Composable
private fun DetectionScreen(detection: DetectionModule, onRestart: () -> Unit, onBack: () -> Unit) {
    val state = detection.fatigue.collectAsState().value
    val sensitivity = remember(detection) { mutableStateOf(detection.engine.sensitivity) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("← 주행 화면으로") }

        FatigueDebugPanel(state = state, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRestart) { Text("처음부터 다시") }
            OutlinedButton(onClick = {
                detection.engine.onTalkResult(answered = false, correct = false, responseMs = 0)
            }) { Text("퀴즈 무응답") }
            OutlinedButton(onClick = {
                detection.engine.onTalkResult(answered = true, correct = true, responseMs = 1500)
            }) { Text("퀴즈 정답") }
        }

        Text("개입 민감도: ${sensitivity.value.label()}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s ->
                val onClick = {
                    detection.engine.sensitivity = s
                    sensitivity.value = s
                }
                if (s == sensitivity.value) {
                    Button(onClick = onClick) { Text(s.label()) }
                } else {
                    OutlinedButton(onClick = onClick) { Text(s.label()) }
                }
            }
        }
    }
}

private fun Sensitivity.label(): String = when (this) {
    Sensitivity.LOW -> "낮음 ($talk/$environment/$rest)"
    Sensitivity.NORMAL -> "보통 ($talk/$environment/$rest)"
    Sensitivity.HIGH -> "높음 ($talk/$environment/$rest)"
}
