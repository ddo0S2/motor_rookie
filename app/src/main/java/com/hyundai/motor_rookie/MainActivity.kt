package com.hyundai.motor_rookie

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.hyundai.motor_rookie.intervention.DrivingRestriction
import com.hyundai.motor_rookie.intervention.EmergencyController
import com.hyundai.motor_rookie.intervention.EmergencyLayer
import com.hyundai.motor_rookie.intervention.EmergencyTrigger
import com.hyundai.motor_rookie.intervention.VehicleEmergencyMonitor
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
import com.hyundai.motor_rookie.ui.PowerNapApp
import com.hyundai.motor_rookie.ui.PowerNapViewModel
import com.hyundai.motor_rookie.ui.Screen as NapScreen
import com.hyundai.motor_rookie.ui.theme.Motor_rookieTheme
import com.hyundai.motor_rookie.vehicle.GearMonitor
import com.hyundai.motor_rookie.vehicle.ScenarioDataSource
import com.hyundai.motor_rookie.vehicle.VehicleSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱 시작점. 1번(감지), 2·3번(대화·공조·창문·쉼터), 4·5번(휴식·파워냅) 파트를 여기서 잇는다.
 *
 *  화면:  처음 설정 → 음성 테스트 → 주행 메인   (GyeolApp)
 *         + 파워냅 화면(PowerNapApp)이 필요할 때만 맨 위에 덮어서 보임
 *  흐름:  차량 데이터 → DetectionModule(졸음 점수) → feedScore()
 *           → 단계가 오르면 "지금 ○○이 필요합니다" 안내(LevelVoice)
 *           → InterventionManager 가 퀴즈 / 공조 / 창문·쉼터 안내를 저절로 시작
 *         휴식 단계에서 쉼터에 도착해 P단 → 저장해 둔 환경으로 파워냅 저절로 시작
 *           → 알람 → 재출발 퀴즈 통과 → 감지 엔진 onRestTaken() (점수·운전 시간 초기화)
 *         퀴즈 결과 → 감지 엔진 onTalkResult() → 대화 반응 점수(s_talk)에 반영
 */
class MainActivity : ComponentActivity() {

    // ───── 감지 모듈 스위치 (1번 파트) ─────
    /**
     * 졸음 점수를 만드는 데이터
     *  SCENARIO : 테스트 모드. 시뮬레이션 패널에서 고른 운전 상태(정상/조금/많이/아주 졸림)로 핸들·속도를 만들어 냄.
     *             기어는 Virtual Console, 퀴즈 중에는 시간 멈춤. 단계별 흐름 확인·시연용 (추천)
     *  CSV      : 녹화된 2시간 34분 주행을 그대로 재생 (단계가 혼자 쭉 진행됨)
     *  LIVE     : 에뮬레이터/실제 차 값 그대로 (Virtual Console 속도·핸들·기어)
     */
    private val dataMode = DataMode.LIVE
    /** 테스트 모드 배속. 5 = 1초에 5초 분량 (평소 운전 학습 3분 → 실제 36초) */
    private val scenarioSpeed = 5.0
    /** CSV 모드: 재생할 데이터와 재생 속도 (60 = 약 2분 30초, 20 = 약 8분) */
    private val simFile = "sim/drowsy_drive.csv"
    private val speedFactor = 60.0
    /** LIVE 모드: true = 시간을 줄임 (기준선 3분, 20분이면 시간 점수 최대). 제출 전 false! */
    private val shortTest = false
    /** LIVE 시연 영상용: 평소 운전 학습 1분, 운전 시간 점수 5분이면 최대, 점수 반응을 빠르게. 제출 전 false! */
    private val liveDemo = true
    // ────────────────────────────────────

    private enum class DataMode { SCENARIO, CSV, LIVE }

    /** 테스트 모드의 데이터 소스 (시뮬레이션 패널 버튼이 운전 상태를 바꾼다) */
    private var scenario by mutableStateOf<ScenarioDataSource?>(null)

    private lateinit var vehicle: Vehicle
    private lateinit var manager: InterventionManager
    private var navi: PleosNavigationController? = null
    /** 비상 모드 (무응답 3회 · 급정거 · 연료 부족 · 행동요령) */
    private lateinit var emergency: EmergencyController
    private var emergencyMonitor: VehicleEmergencyMonitor? = null
    private var napping = false
    private var settings by mutableStateOf(InterventionSettings())

    /** 앱 전체 공용 음성 (Pleos TTS). 단계 안내와 퀴즈가 같이 쓴다. */
    private lateinit var speaker: PleosSpeaker
    private lateinit var levelVoice: LevelVoice

    /** 감지 모듈. "감지 처음부터 다시"를 누르면 새로 만든다. */
    private var detection by mutableStateOf<DetectionModule?>(null)
    /** true = 졸음 지수를 감지 모듈에서 받음, false = 시뮬레이션 패널 슬라이더 */
    private var useDetection by mutableStateOf(true)
    private var bridgeJob: Job? = null

    /** 4·5번 파트: 파워냅 (P단 감지, 시트·조명·공조, 알람, 재출발 퀴즈) */
    private val napVm: PowerNapViewModel by viewModels()
    /** 파워냅 환경 저장 화면을 연 상태인지 (처음 설정 / 시뮬레이션 패널에서 열기) */
    private var napSettingsOpen by mutableStateOf(false)
    /** 마지막으로 휴식 단계였던 시각 (파워냅 자동 시작 판단용) */
    private var lastRestAt = -REST_GRACE_MS

    // 권한은 처음 설정 화면의 [허용] 버튼에서 한 번에 묻는다 (CAR_SPEED, CAR_DRIVE, 마이크, 위치).
    // D단에서는 권한 창이 막히므로, 그때는 adb pm grant 로 허용.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 파워냅 중 화면이 꺼져 타이머 표시가 멈추지 않도록
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // enableEdgeToEdge()  ← 하단 Pleos 독(공조 바) 뒤로 화면이 숨어서 끔

        vehicle = Vehicle(this)
        vehicle.initialize()

        // 주행 중 규제 상태 (distractionOptimized 앱은 CarUxRestrictions 를 따라야 함)
        DrivingRestriction.start(this)

        // 실제 Pleos 내비 (연결 실패해도 앱은 가짜 내비로 계속 동작)
        navi = runCatching { PleosNavigationController(this) }.getOrNull()
        // 내비에서 길안내가 끝나면 곁으로 돌아올지 (시뮬레이션 패널 스위치)
        navi?.autoReturn = { settings.naviAutoReturn }

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

        // 비상 모드
        emergency = EmergencyController(
            context = this,
            scope = lifecycleScope,
            voiceOut = PleosVoiceOutput(speaker),
            realInput = AndroidStt(this),
            settings = { settings },
            navigation = { navi?.takeIf { settings.useRealNavi } ?: manager.simNavigation },
            simNavigation = manager.simNavigation,
            simLocation = manager.simLocation,
            realLocation = AndroidLocationSource(this),
            restRepo = RestRepository(this),
            pleosNavi = navi,
        )
        // 실제 차량 신호: 급정거 · 연료 부족
        emergencyMonitor = VehicleEmergencyMonitor(
            context = this,
            onHardBrake = { runOnUiThread { emergency.start(EmergencyTrigger.HARD_BRAKE) } },
            onFuelLow = { km -> runOnUiThread { emergency.fuelLow(km) } },
        ).also { it.start() }
        // 비상 모드 중에는 퀴즈·공조·쉼터 안내를 멈춤
        lifecycleScope.launch {
            emergency.ui.map { it.active }.distinctUntilChanged().collect { on ->
                manager.paused = on || napping
            }
        }

        // 퀴즈 결과 → 감지 엔진의 대화 반응 점수(s_talk)
        lifecycleScope.launch {
            manager.events.collect { e ->
                // 무응답 3회 연속 → 비상 모드
                if (e == InterventionEvent.Emergency) emergency.start(EmergencyTrigger.NO_RESPONSE)
                if (e is InterventionEvent.QuizFinished && useDetection) {
                    val engine = detection?.engine ?: return@collect
                    e.results.forEach { r ->
                        engine.onTalkResult(answered = !r.noAnswer, correct = r.correct, responseMs = r.responseMs)
                    }
                }
            }
        }

        // 파워냅은 졸음 단계가 '휴식'일 때 P단으로 바꾸면 저절로 시작 (평소 신호 대기 P단에서는 안 켜짐)
        // 쉼터까지 가는 동안(또는 P로 바꾼 직후) 점수가 조금 내려가 휴식 단계를 벗어날 수 있어서,
        // "지금 휴식 단계"이거나 "최근 10분 안에 휴식 단계였으면" 허용한다.
        napVm.autoStartAllowed = {
            manager.ui.value.stage == Stage.REST ||
                    android.os.SystemClock.elapsedRealtime() - lastRestAt < REST_GRACE_MS
        }
        lifecycleScope.launch {
            var prev = Stage.OBSERVE
            manager.ui.map { it.stage }.distinctUntilChanged().collect { st ->
                // 휴식 단계에 들어갈 때와 벗어날 때 시각을 적어 둔다 (벗어난 뒤 10분까지 허용)
                if (st == Stage.REST || prev == Stage.REST) lastRestAt = android.os.SystemClock.elapsedRealtime()
                prev = st
            }
        }
        // 휴식 단계에서 P로 바꿨는데 파워냅 환경이 저장돼 있지 않으면 → 안내하고 저장 화면을 연다
        lifecycleScope.launch {
            napVm.state.map { it.isParked }.distinctUntilChanged().collect { parked ->
                val st = napVm.state.value
                if (parked && st.saved == null && napVm.autoStartAllowed()) {
                    levelVoice.speak("파워냅 환경이 저장돼 있지 않아요. 저장하면 바로 쉴 수 있어요.")
                    napSettingsOpen = true
                }
            }
        }
        lifecycleScope.launch {
            napVm.state.map { it.screen }.distinctUntilChanged().collect { screen ->
                val napping = screen !in setOf(NapScreen.SETTINGS, NapScreen.ALARM_SOUND, NapScreen.PASSED)
                this@MainActivity.napping = napping
                manager.paused = napping || emergency.active   // 파워냅·비상 모드 중에는 퀴즈·공조·쉼터 안내를 멈춤
                if (screen == NapScreen.PASSED) {
                    // 재출발 퀴즈 통과 = 휴식 완료 → 졸음 점수·운전 시간 초기화
                    detection?.engine?.onRestTaken()
                    // 테스트 모드: 푹 쉬었으니 운전 상태도 '정상'으로 (안 바꾸면 '아주 졸림'이라 바로 다시 올라감)
                    scenario?.setLevel(0)
                    Log.d(TAG, "파워냅 완료 → onRestTaken()")
                }
                if (screen == NapScreen.SETTINGS) { this@MainActivity.napping = false; manager.paused = emergency.active }
            }
        }

        startDetection()

        setContent {
            Motor_rookieTheme {
                val d = detection
                val live = d?.fatigue?.collectAsState()?.value
                val nap by napVm.state.collectAsState()
                val sc = scenario
                val scLevel = sc?.level?.collectAsState()?.value
                val napActive = nap.screen != NapScreen.SETTINGS && nap.screen != NapScreen.ALARM_SOUND
                Box(Modifier.fillMaxSize()) {
                    // 처음 설정 → 음성 테스트 → 주행 메인
                    GyeolApp(
                        manager = manager,
                        settings = settings,
                        onSettingsChange = {
                            settings = it
                            InterventionSettingsStore.save(this@MainActivity, it)
                        },
                        detection = DetectionLink(
                            live = live,
                            useDetection = useDetection,
                            onUseDetectionChange = { useDetection = it },
                            feedScore = { feedScore(it) },
                            onRestart = { startDetection() },
                            onOpenPowerNap = { napSettingsOpen = true },
                            scenarioLevel = scLevel,
                            onScenarioLevel = sc?.let { src -> { lv: Int -> src.setLevel(lv) } },
                        ),
                        emergency = emergency,
                    )
                    // 파워냅이 진행 중이거나 환경 저장 화면을 열었을 때만 맨 위에 덮는다.
                    // (Surface 가 아래 화면으로 터치가 새지 않게 막아 준다. 아래 화면은 그대로 살아 있음)
                    if (napActive || napSettingsOpen) {
                        Surface(Modifier.fillMaxSize()) {
                            PowerNapApp(onExit = { napSettingsOpen = false }, vm = napVm)
                        }
                    }
                    // 비상 화면 · 연료 부족 · 행동요령 (맨 위)
                    EmergencyLayer(emergency)
                }
            }
        }
    }

    /** 감지 모듈을 (다시) 만들고, 점수가 바뀔 때마다 개입 매니저에 넣는다 */
    private fun startDetection() {
        bridgeJob?.cancel()
        detection?.stop()
        val source = when (dataMode) {
            DataMode.SCENARIO -> ScenarioDataSource(
                scope = lifecycleScope,
                speedFactor = scenarioSpeed,
                startHour = 23,
                parkedFlow = GearMonitor(this).parkedFlow(),   // 기어는 Virtual Console 을 따름
            )
            else -> VehicleSources.create(
                context = this,
                scope = lifecycleScope,
                simulation = dataMode == DataMode.CSV,
                simFile = simFile,
                speedFactor = speedFactor,
            )
        }
        scenario = source as? ScenarioDataSource
        val engine = when {
            // LIVE 시연 영상용: 시간 관련 숫자만 줄임 (가중치·판단 기준은 실제와 같음)
            dataMode == DataMode.LIVE && liveDemo ->
                FatigueEngine(FatigueConfig(baselineSec = 60, fullFatigueSec = 300.0, smoothingSec = 8.0,
                    highwaySpeedKmh = 40f,          // 40km/h 이상이면 분석 (원래 60)
                    steadyTrendKmhPerSec = 3.0,
                ))// 속도가 조금 출렁여도 '일정 주행'으로 인정 (원래 0.3)))
            // 테스트 모드: 평소 운전 학습만 3분으로 줄임 (나머지 계산은 실제와 같음)
            dataMode == DataMode.SCENARIO -> FatigueEngine(FatigueConfig(baselineSec = 180))
            shortTest -> FatigueEngine(FatigueConfig(baselineSec = 180, fullFatigueSec = 1200.0))
            else -> FatigueEngine()
        }
        val module = DetectionModule(source, lifecycleScope, engine)
        module.start()
        detection = module
        bridgeJob = lifecycleScope.launch {
            var lastFormulaLog = 0L
            module.fatigue.collect { s ->
                if (useDetection && !manager.paused) feedScore(s.score)
                // 시연용: 2초마다 계산식에 들어간 값을 Logcat 에 보여 줌 (필터 tag:FATIGUE)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFormulaLog >= 2_000) {
                    lastFormulaLog = now
                    logFormula(s)
                }
            }
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

    /**
     * 피로 점수 계산식을 그대로 풀어서 Logcat(tag:FATIGUE)에 찍는다.
     *   F = 100 × min(1, m × (0.25·s_time + 0.30·s_steer + 0.15·s_speed + 0.30·s_talk + 0.15·s_signal))
     * 표시 점수는 튀지 않게 부드럽게 따라가서, 계산값과 잠깐 다를 수 있다.
     */
    private fun logFormula(s: com.hyundai.motor_rookie.fatigue.FatigueState) {
        val g = s.signals
        val sum = 0.25 * g.time + 0.30 * g.steer + 0.15 * g.speed + 0.30 * g.talk + 0.15 * g.signal
        val raw = (100 * minOf(1.0, s.multiplier * sum)).toInt()
        fun f(v: Double) = "%.2f".format(v)
        val why = if (s.reasons.isEmpty()) "" else " (" + s.reasons.joinToString(" · ") + ")"
        Log.i(
            "FATIGUE",
            "F = 100 × ${f(s.multiplier)}$why × (0.25·시간 ${f(g.time)} + 0.30·핸들 ${f(g.steer)} + " +
                    "0.15·속도 ${f(g.speed)} + 0.30·대화 ${f(g.talk)} + 0.15·경고 ${f(g.signal)}) = $raw" +
                    "  → 표시 점수 ${s.score} · ${s.level} · 운전 ${s.drivingMinutes}분" +
                    if (s.baselineReady) "" else " · 평소 운전 학습 중"
        )
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
        emergencyMonitor?.stop()
        DrivingRestriction.stop()
        emergency.shutdown()
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MAIN"
        /** 휴식 단계를 벗어난 뒤에도 이 시간 안에 P로 바꾸면 파워냅을 시작한다 */
        const val REST_GRACE_MS = 10 * 60_000L
    }
}