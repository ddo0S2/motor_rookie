package com.hyundai.motor_rookie

import ai.pleos.playground.vehicle.Vehicle
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.hyundai.motor_rookie.intervention.AndroidStt
import com.hyundai.motor_rookie.intervention.AndroidTts
import com.hyundai.motor_rookie.intervention.GyeolApp
import com.hyundai.motor_rookie.intervention.InterventionManager
import com.hyundai.motor_rookie.intervention.InterventionSettings
import com.hyundai.motor_rookie.intervention.InterventionSettingsStore
import com.hyundai.motor_rookie.intervention.ManualVoiceInput
import com.hyundai.motor_rookie.intervention.PleosClimateController
import com.hyundai.motor_rookie.intervention.PleosNavigationController
import com.hyundai.motor_rookie.intervention.AndroidLocationSource
import com.hyundai.motor_rookie.intervention.RestRepository
import com.hyundai.motor_rookie.intervention.SimClimateController
import com.hyundai.motor_rookie.ui.theme.Motor_rookieTheme

class MainActivity : ComponentActivity() {
    private lateinit var vehicle: Vehicle
    private lateinit var manager: InterventionManager
    private var navi: PleosNavigationController? = null
    private var settings by mutableStateOf(InterventionSettings())

    // 마이크 권한 요청 (음성 퀴즈)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge()  ← 하단 Pleos 독(공조 바) 뒤로 화면이 숨어서 끔

        vehicle = Vehicle(this)
        vehicle.initialize()

        // 실제 Pleos 내비 (연결 실패해도 앱은 가짜 내비로 계속 동작)
        navi = runCatching { PleosNavigationController(this) }.getOrNull()

        settings = InterventionSettingsStore.load(this)
        manager = InterventionManager(
            scope = lifecycleScope,
            voiceOut = AndroidTts(this),
            realInput = AndroidStt(this),
            manualInput = ManualVoiceInput(),
            realClimate = PleosClimateController(vehicle.getHVAC()),
            simClimate = SimClimateController(),
            settings = { settings },
            // 3단계: 쉼터 데이터 · 위치 · 실제 내비 (창문은 API 확인 전까지 시뮬레이션)
            restRepo = RestRepository(this),
            realLocation = AndroidLocationSource(this),
            realNavigation = navi
        )

        // 권한 팝업이 멀티윈도우에서 안 보이는 문제 → adb로 권한 부여
        // micPermission.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            Motor_rookieTheme {
                // 처음 설정 → 음성 테스트 → 주행 메인
                GyeolApp(
                    manager = manager,
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        InterventionSettingsStore.save(this, it)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        manager.shutdown()
        navi?.release()
        vehicle.release()
        super.onDestroy()
    }
}
