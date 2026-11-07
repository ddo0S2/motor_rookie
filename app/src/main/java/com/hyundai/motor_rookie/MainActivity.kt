package com.hyundai.motor_rookie

import ai.pleos.playground.vehicle.Vehicle
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.hyundai.motor_rookie.alert.LevelVoice
import com.hyundai.motor_rookie.alert.PleosSpeaker
import com.hyundai.motor_rookie.fatigue.DetectionModule
import com.hyundai.motor_rookie.fatigue.FatigueConfig
import com.hyundai.motor_rookie.fatigue.FatigueEngine
import com.hyundai.motor_rookie.fatigue.FatigueLevel
import com.hyundai.motor_rookie.intervention.AndroidLocationSource
import com.hyundai.motor_rookie.intervention.AndroidStt
import com.hyundai.motor_rookie.intervention.DetectionLink
import com.hyundai.motor_rookie.intervention.GyeolApp
import com.hyundai.motor_rookie.intervention.InterventionEvent
import com.hyundai.motor_rookie.intervention.InterventionManager
import com.hyundai.motor_rookie.intervention.InterventionSettings
import com.hyundai.motor_rookie.intervention.InterventionSettingsStore
import com.hyundai.motor_rookie.intervention.ManualVoiceInput
import com.hyundai.motor_rookie.intervention.PleosClimateController
import com.hyundai.motor_rookie.intervention.PleosNavigationController
import com.hyundai.motor_rookie.intervention.PleosVoiceOutput
import com.hyundai.motor_rookie.intervention.RestRepository
import com.hyundai.motor_rookie.intervention.SimClimateController
import com.hyundai.motor_rookie.intervention.Stage
import com.hyundai.motor_rookie.ui.theme.Motor_rookieTheme
import com.hyundai.motor_rookie.vehicle.VehicleSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 앱 시작점. 1번 파트(감지)와 2·3번 파트(대화·공조·창문·쉼터)를 여기서 잇는다.
 *
 *  화면:  처음 설정 → 음성 테스트 → 주행 메인   (GyeolApp, 2·3번 파트 화면)
 *  흐름:  차량 데이터 → DetectionModule(졸음 점수) → feedScore()
 *           → 단계가 오르면 "지금 ○○이 필요합니다" 안내(LevelVoice)
 *           → InterventionManager 가 퀴즈 / 공조 / 창문·쉼터 안내를 저절로 시작
 *         퀴즈 결과 → 감지 엔진 onTalkResult() → 대화 반응 점수(s_talk)에 반영
 */
class MainActivity : ComponentActivity() {

    // ───── 감지 모듈 스위치 (1번 파트) ─────
    /** true = CSV 시연 데이터 재생, false = 에뮬레이터/실제 차 값 (Virtual Console) */
    private val useSimulation = true
    /** 재생할 데이터: "sim/drowsy_drive.csv"(졸음 운전) 또는 "sim/normal_drive.csv"(정상 운전) */
    private val simFile = "sim/drowsy_drive.csv"
    /** CSV 재생 속도. 60 = 약 2분 30초, 20 = 약 8분 */
    private val speedFactor = 60.0
    /** true = 에뮬레이터 실시간 테스트용으로 시간을 줄임 (기준선 3분, 20분이면 시간 점수 최대). 제출 전 false! */
    private val shortTest = false
    // ────────────────────────────────────

    private lateinit var vehicle: Vehicle
    private lateinit var manager: InterventionManager
    private var navi: PleosNavigationController? = null
    private var settings by mutableStateOf(InterventionSettings())

    /** 앱 전체 공용 음성 (Pleos TTS). 단계 안내와 퀴즈가 같이 쓴다. */
    private lateinit var speaker: PleosSpeaker
    private lateinit var levelVoice: LevelVoice

    /** 감지 모듈. "감지 처음부터 다시"를 누르면 새로 만든다. */
    private var detection by mutableStateOf<DetectionModule?>(null)
    /** true = 졸음 지수를 감지 모듈에서 받음, false = 시뮬레이션 패널 슬라이더 */
    private var useDetection by mutableStateOf(true)
    private var bridgeJob: Job? = null

    /**
     * 차량 권한(속도 CAR_SPEED, 핸들·기어 CAR_DRIVE)을 묻는 창. 허용하면 감지를 다시 시작한다.
     * 주의: 이 창은 시스템 화면이라 D단에서는 막힌다 → P단에서 허용하거나 adb pm grant 로 허용.
     */
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            Log.d(TAG, "권한 결과: $result")
            if (result.values.all { it }) startDetection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge()  ← 하단 Pleos 독(공조 바) 뒤로 화면이 숨어서 끔

        vehicle = Vehicle(this)
        vehicle.initialize()

        // 실제 Pleos 내비 (연결 실패해도 앱은 가짜 내비로 계속 동작)
        navi = runCatching { PleosNavigationController(this) }.getOrNull()

        speaker = PleosSpeaker(this)
        levelVoice = LevelVoice(speaker, lifecycleScope)

        settings = InterventionSettingsStore.load(this)
        manager = InterventionManager(
            scope = lifecycleScope,
            voiceOut = PleosVoiceOutput(speaker),   // AndroidTts 는 Pleos 에뮬레이터에서 소리가 안 나서 교체
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

        // 퀴즈 결과 → 감지 엔진의 대화 반응 점수(s_talk)
        lifecycleScope.launch {
            manager.events.collect { e ->
                if (e is InterventionEvent.QuizFinished && useDetection) {
                    val engine = detection?.engine ?: return@collect
                    e.results.forEach { r ->
                        engine.onTalkResult(answered = !r.noAnswer, correct = r.correct, responseMs = r.responseMs)
                    }
                }
            }
        }

        startDetection()
        val missing = CAR_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (!useSimulation && missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())

        setContent {
            Motor_rookieTheme {
                val d = detection
                val live = d?.fatigue?.collectAsState()?.value
                // 처음 설정 → 음성 테스트 → 주행 메인
                GyeolApp(
                    manager = manager,
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        InterventionSettingsStore.save(this, it)
                    },
                    detection = DetectionLink(
                        live = live,
                        useDetection = useDetection,
                        onUseDetectionChange = { useDetection = it },
                        feedScore = { feedScore(it) },
                        onRestart = { startDetection() },
                    ),
                )
            }
        }
    }

    /** 감지 모듈을 (다시) 만들고, 점수가 바뀔 때마다 개입 매니저에 넣는다 */
    private fun startDetection() {
        bridgeJob?.cancel()
        detection?.stop()
        val source = VehicleSources.create(
            context = this,
            scope = lifecycleScope,
            simulation = useSimulation,
            simFile = simFile,
            speedFactor = speedFactor,
        )
        val engine = if (shortTest) {
            FatigueEngine(FatigueConfig(baselineSec = 180, fullFatigueSec = 1200.0))
        } else {
            FatigueEngine()
        }
        val module = DetectionModule(source, lifecycleScope, engine)
        module.start()
        detection = module
        bridgeJob = lifecycleScope.launch {
            module.fatigue.collect { s -> if (useDetection) feedScore(s.score) }
        }
    }

    /**
     * 점수를 개입 매니저에 넣는다. 단계가 "오르는" 순간이면 먼저 단계 안내 음성을 줄 세워서
     * "지금 대화가 필요합니다" → 퀴즈 질문 순서로 들리게 한다.
     * (오를 때 기준은 InterventionManager 와 같은 40/60/75 + 민감도. 내려갈 때는 안내 없음)
     */
    private fun feedScore(score: Int) {
        val off = settings.sensitivity.offset
        val target = when {
            score >= InterventionManager.REST_THRESHOLD + off -> Stage.REST
            score >= InterventionManager.CLIMATE_THRESHOLD + off -> Stage.CLIMATE
            score >= InterventionManager.TALK_THRESHOLD + off -> Stage.TALK
            else -> Stage.OBSERVE
        }
        if (target.ordinal > manager.ui.value.stage.ordinal) levelVoice.announce(target.toLevel())
        manager.onFatigueScore(score)
    }

    private fun Stage.toLevel(): FatigueLevel = when (this) {
        Stage.OBSERVE -> FatigueLevel.NORMAL
        Stage.TALK -> FatigueLevel.TALK
        Stage.CLIMATE -> FatigueLevel.ENVIRONMENT
        Stage.REST -> FatigueLevel.REST
    }

    override fun onDestroy() {
        bridgeJob?.cancel()
        detection?.stop()
        levelVoice.stop()
        manager.shutdown()
        speaker.release()
        navi?.release()
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MAIN"
        val CAR_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",   // 속도 (안드로이드 차량 표준 API)
            "pleos.car.permission.CAR_DRIVE",     // 핸들·기어 (Pleos SDK)
        )
    }
}
