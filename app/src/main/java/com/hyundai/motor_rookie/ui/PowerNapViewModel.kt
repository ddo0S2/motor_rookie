package com.hyundai.motor_rookie.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyundai.motor_rookie.data.AlarmSound
import com.hyundai.motor_rookie.data.AlarmSoundRepository
import com.hyundai.motor_rookie.data.AnswerMatcher
import com.hyundai.motor_rookie.data.CabinMapper
import com.hyundai.motor_rookie.data.LocalQuizRepository
import com.hyundai.motor_rookie.data.NapSettings
import com.hyundai.motor_rookie.data.QuizRepository
import com.hyundai.motor_rookie.data.SettingsRepository
import com.hyundai.motor_rookie.speech.AlarmPlayer
import com.hyundai.motor_rookie.speech.PleosSpeechGateway
import com.hyundai.motor_rookie.speech.SpeechGateway
import com.hyundai.motor_rookie.vehicle.CabinCapabilities
import com.hyundai.motor_rookie.vehicle.CabinState
import com.hyundai.motor_rookie.vehicle.FakeVehicleGateway
import com.hyundai.motor_rookie.vehicle.GearMonitor
import com.hyundai.motor_rookie.vehicle.PleosVehicleGateway
import com.hyundai.motor_rookie.vehicle.VehicleGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** SDK 연동 전 화면 흐름만 볼 때 true 로 바꾸세요. */
private const val USE_FAKE_VEHICLE = false

/**
 * 파워냅 빠른 테스트: true 면 "1분"을 4초로 줄입니다. (제출·실차 시연 전 false!)
 *  15분 타이머 → 60초, 최소 5분 → 20초, 5분 연장 → 20초, 10분 더 쉬기 → 40초, 재확인 대기 5분 → 20초
 */
const val NAP_FAST_TEST = true
private val SEC_PER_MIN = if (NAP_FAST_TEST) 4 else 60

/** "실제 초"를 테스트 배속에 맞춘 초로 바꿉니다. (5분 = 300초 → 빠른 테스트에서는 20초) */
private fun napSec(realSec: Int) = realSec * SEC_PER_MIN / 60

enum class Screen {
    SETTINGS,        // 파워냅 세팅 (홈)
    ALARM_SOUND,     // 알람 소리 고르기 (세팅의 하위 화면)
    TIMER_SETUP,     // P단 전환 직후: 시간 조절 (±5분/±1분, 취소/완료)
    TIMER_CONFIRM,   // 정한 시간 확인 + 시작
    NAPPING,         // 진행 중 (5분 연장 / 지금 깨우기)
    QUIZ,            // 각성 확인 퀴즈
    QUIZ_FAILED,     // 5분만 더 쉬어요
    STRETCHING,      // 스트레칭 안내
    PASSED,          // 출발해도 좋아요
}

/**
 * 스트레칭 한 동작.
 * @param spoken 음성으로 읽어 줄 안내
 * @param holdSec 안내를 읽은 뒤 사용자가 따라 할 시간(초). 이 시간이 지나면 다음 동작을 안내합니다.
 */
data class StretchStep(
    val name: String,
    val how: String,
    val spoken: String,
    val holdSec: Int,
)

enum class QuizPhase { INTRO, ASKING, LISTENING, CORRECT, WRONG }

data class UiState(
    val screen: Screen = Screen.SETTINGS,
    val isParked: Boolean = false,
    val capabilities: CabinCapabilities = CabinCapabilities(),
    val draft: NapSettings = NapSettings.DEFAULT,
    val saved: NapSettings? = null,
    /** 기기의 시스템 알람음 목록 */
    val alarmSounds: List<AlarmSound> = emptyList(),
    /** 알람 소리 화면에서 지금 골라 둔 소리 (아직 세팅에 반영 전) */
    val pickedAlarmUri: String? = null,
    val saveFlash: Boolean = false,
    val timerMinutes: Int = NapRules.DEFAULT_MINUTES,
    val remainingSec: Int = 0,
    val napTotalSec: Int = 0,
    val napElapsedMin: Int = 0,
    val quizIndex: Int = 0,
    val quizTotal: Int = NapRules.QUIZ_COUNT,
    val quizPhase: QuizPhase = QuizPhase.INTRO,
    val quizDisplay: String = "",
    val quizChoices: List<String> = emptyList(),
    val quizResults: List<Boolean?> = emptyList(),
    val listenSecLeft: Int = 0,
    val heardText: String = "",
    val retrySecLeft: Int = 0,
    val avgReactionSec: Float = 0f,
    /** 지금 음성으로 안내 중인 스트레칭 동작 번호(0부터). 안내 중이 아니면 -1 */
    val stretchStep: Int = -1,
    val notice: String? = null,
)

object NapRules {
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 30
    const val EXTEND_SEC = 5 * 60
    const val REST_MORE_SEC = 10 * 60
    const val RETRY_SEC = 5 * 60
    const val QUIZ_COUNT = 3
    const val LISTEN_MS = 10_000L

    // 타이머 조절 화면 안내 (화면 문구 + 음성)
    const val SETUP_GUIDE_TITLE = "얼마나 쉴까요?"
    const val SETUP_GUIDE_SUB = "버튼으로 시간을 정하고 완료를 눌러 주세요"
    const val SETUP_TTS = "얼마나 쉴까요? 버튼으로 시간을 정하고 완료를 눌러 주세요."

    // 시간 확인 화면 안내 (화면 문구 + 음성)
    fun confirmTitle(minutes: Int) = "${minutes}분으로 맞췄어요"
    const val CONFIRM_GUIDE_SUB = "파워냅을 시작할까요?"
    fun confirmTts(minutes: Int) = "${minutes}분으로 타이머를 맞췄어요. 파워냅을 시작할까요?"

    const val INTRO_TTS = "다시 출발할 준비 되셨나요? 간단한 질문 하나에 답해 주세요."
    const val WAKE_TTS = "일어날 시간이에요."
    const val CORRECT_TTS = "정답이에요."
    const val FAILED_TTS = "아직 반응이 조금 느려요. 5분만 더 쉬었다가 다시 확인할게요."
    const val PASSED_TTS = "출발해도 좋아요. 안전 운전하세요."

    // 스트레칭 안내 (화면 문구 + 음성)
    val STRETCH_STEPS = listOf(
        StretchStep(
            "목 돌리기", "고개를 천천히 오른쪽으로 다섯 번, 왼쪽으로 다섯 번 돌려요.",
            "1번, 목 돌리기. 고개를 천천히 오른쪽으로 다섯 번, 왼쪽으로 다섯 번 돌려 주세요.", 15,
        ),
        StretchStep(
            "어깨 으쓱하기", "어깨를 귀까지 끌어올렸다가 툭 내려놓기를 다섯 번 해요.",
            "2번, 어깨 으쓱하기. 어깨를 귀까지 끌어올렸다가 툭 내려놓기를 다섯 번 해 주세요.", 12,
        ),
        StretchStep(
            "손목·손가락 풀기", "주먹을 꽉 쥐었다가 활짝 펴기를 열 번 해요.",
            "3번, 손목과 손가락 풀기. 주먹을 꽉 쥐었다가 활짝 펴기를 열 번 해 주세요.", 12,
        ),
        StretchStep(
            "허리 비틀기", "등받이를 잡고 상체를 좌우로 천천히 비틀어 10초씩 버텨요.",
            "4번, 허리 비틀기. 등받이를 잡고 상체를 좌우로 천천히 비틀어 10초씩 버텨 주세요.", 22,
        ),
        StretchStep(
            "깊게 숨쉬기", "코로 4초 들이마시고 입으로 6초 내쉬기를 세 번 해요.",
            "5번, 깊게 숨쉬기. 코로 4초 들이마시고 입으로 6초 내쉬기를 세 번 해 주세요.", 30,
        ),
    )
    const val STRETCH_INTRO_TTS = "스트레칭을 안내해 드릴게요. 하나씩 따라 해 보세요."
    const val STRETCH_DONE_TTS = "스트레칭을 마쳤어요. 준비되면 다시 확인을 눌러 주세요."
}

class PowerNapViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val quizRepo: QuizRepository = LocalQuizRepository()
    private val vehicle: VehicleGateway =
        if (USE_FAKE_VEHICLE) FakeVehicleGateway() else PleosVehicleGateway(app)
    private val speech: SpeechGateway = PleosSpeechGateway(app)
    private val gear = GearMonitor(app)
    private val alarm = AlarmPlayer(app)
    private val alarmSoundRepo = AlarmSoundRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 파워냅 환경을 적용하기 직전의 차량 상태. 원상복귀에 사용합니다. */
    private var snapshot: CabinState? = null

    private var napEndAt = 0L
    private var napStartedAt = 0L
    private var sessionNapSec = 0L
    private var timerJob: Job? = null
    private var quizJob: Job? = null
    private var retryJob: Job? = null
    private var flashJob: Job? = null
    /** 화면 안내 음성. 다른 화면으로 넘어가면 읽던 안내는 멈춥니다. */
    private var guideJob: Job? = null
    /** 차량 제어 작업(적용·복원). 화면 흐름과 분리해 뒤에서 순서대로 실행합니다. */
    private var cabinJob: Job? = null
    private var touchAnswer: CompletableDeferred<String>? = null
    private var gearInitialized = false

    /**
     * P단으로 바꿨을 때 파워냅을 저절로 시작해도 되는지. MainActivity 가 정한다.
     * (곁 앱에서는 졸음 단계가 '휴식'일 때만 저절로 시작. 기본값 true = 파워냅 앱 단독 실행)
     */
    var autoStartAllowed: () -> Boolean = { true }

    init {
        viewModelScope.launch {
            runCatching { vehicle.connect() }.onFailure { Log.e(TAG, "Vehicle 연결 실패", it) }
            val caps = vehicle.capabilities()
            val saved = settingsRepo.load()
            _state.update { it.copy(capabilities = caps, saved = saved, draft = saved ?: NapSettings.DEFAULT) }
        }
        viewModelScope.launch {
            val sounds = withContext(Dispatchers.IO) { alarmSoundRepo.load() }
            _state.update { it.copy(alarmSounds = sounds) }
        }
        viewModelScope.launch {
            runCatching { speech.initialize() }.onFailure { Log.e(TAG, "음성 SDK 초기화 실패", it) }
        }
        viewModelScope.launch {
            try {
                gear.parkedFlow().collect(::onGearChanged)
            } catch (e: Exception) {
                Log.e(TAG, "기어 상태 구독 실패", e)
                _state.update { it.copy(notice = "기어 상태를 읽지 못했어요. 차량 연결을 확인해 주세요.") }
            }
        }
    }

    // ───────────── 파워냅 세팅 ─────────────

    fun setSeatDegrees(value: Int) = updateDraft {
        it.copy(seatDegrees = value.coerceIn(NapSettings.SEAT_MIN_DEG, NapSettings.SEAT_MAX_DEG))
    }

    fun setTemperature(value: Int) = updateDraft {
        it.copy(temperature = value.coerceIn(NapSettings.TEMP_MIN, NapSettings.TEMP_MAX))
    }

    fun setLightPercent(value: Int) = updateDraft { it.copy(lightPercent = value.coerceIn(0, 100)) }

    /** 파워냅 중 공조(에어컨·히터)를 켤지 끌지 */
    fun setHvacOn(on: Boolean) = updateDraft { it.copy(hvacOn = on) }

    fun resetToDefaults() = updateDraft { NapSettings.DEFAULT }

    fun saveSettings() {
        val draft = _state.value.draft
        settingsRepo.save(draft)
        _state.update { it.copy(saved = draft, saveFlash = true, notice = null) }
        // 디자인: 저장 버튼이 1.2초 동안 초록색 '저장 완료'로 바뀝니다.
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            delay(1_200)
            _state.update { it.copy(saveFlash = false) }
        }
    }

    // ───────────── 알람 소리 고르기 ─────────────

    /** 세팅의 '알람 소리' 카드: 고르기 화면으로 이동합니다. 지금 설정된 소리가 선택된 상태로 열립니다. */
    fun openAlarmPicker() = _state.update {
        it.copy(screen = Screen.ALARM_SOUND, pickedAlarmUri = it.draft.alarmSoundUri, notice = null)
    }

    /** 목록에서 소리를 누르면 고르고 바로 미리 들려줍니다. */
    fun pickAlarmSound(uri: String) {
        _state.update { it.copy(pickedAlarmUri = uri) }
        alarm.preview(uri)
    }

    /** '이 소리로 선택': 세팅 화면에 반영합니다. 실제 저장은 세팅 화면의 '저장' 버튼으로 합니다. */
    fun confirmAlarmSound() {
        alarm.stopPreview()
        _state.update {
            it.copy(
                screen = Screen.SETTINGS,
                draft = it.draft.copy(alarmSoundUri = it.pickedAlarmUri),
            )
        }
    }

    /** 고르지 않고 돌아가기: 세팅은 바뀌지 않습니다. */
    fun closeAlarmPicker() {
        alarm.stopPreview()
        _state.update { it.copy(screen = Screen.SETTINGS) }
    }

    // ───────────── 이전 화면 ('이전' 버튼 + 시스템 뒤로 가기) ─────────────

    /**
     * 화면 위쪽 '이전' 버튼과 시스템 뒤로 가기가 같은 동작을 합니다.
     * 세팅 화면에서는 호출되지 않습니다(앱 종료는 시스템이 처리).
     */
    fun onBack() {
        when (_state.value.screen) {
            Screen.SETTINGS -> Unit
            Screen.ALARM_SOUND -> closeAlarmPicker()
            // 타이머 조절은 파워냅의 첫 화면이라, 이전 = 세팅 화면 = 파워냅 취소(원상복귀)입니다.
            Screen.TIMER_SETUP -> cancelNap()
            Screen.TIMER_CONFIRM -> openTimerSetup()
            Screen.NAPPING -> backFromNapping()
            Screen.QUIZ -> backFromQuiz()
            Screen.QUIZ_FAILED -> retryQuizNow()
            Screen.STRETCHING -> closeStretching()
            Screen.PASSED -> retryQuizNow()
        }
    }

    /** 진행 중 → 시간 확인 화면. 타이머를 멈추고, 파워냅 환경은 그대로 유지합니다. */
    private fun backFromNapping() {
        timerJob?.cancel()
        sessionNapSec += (SystemClock.elapsedRealtime() - napStartedAt) / 1000
        // 알람이 울리는 순간에 누른 경우 원상복귀가 시작됐을 수 있으므로 환경을 다시 적용합니다.
        settingsRepo.load()?.let { applyNapCabin(it) }
        _state.update { it.copy(screen = Screen.TIMER_CONFIRM) }
        announceConfirm()
    }

    /** 퀴즈 → 시간 확인 화면. 퀴즈를 멈추고 파워냅 환경을 다시 적용해 더 쉴 시간을 고르게 합니다. */
    private fun backFromQuiz() {
        quizJob?.cancel()
        retryJob?.cancel()
        speech.stop()
        settingsRepo.load()?.let { applyNapCabin(it) }
        _state.update { it.copy(screen = Screen.TIMER_CONFIRM) }
        announceConfirm()
    }

    // ───────────── 파워냅 실행 ─────────────

    /** P단 전환 시 자동 호출. 이미 P단일 때는 상단 '바로 시작' 칩으로 호출합니다. */
    fun startNapSession() {
        val saved = settingsRepo.load() ?: return
        sessionNapSec = 0
        // 화면을 먼저 넘기고(바로 타이머 조절 화면), 시트·조명·온도는 뒤에서 적용합니다.
        _state.update {
            it.copy(screen = Screen.TIMER_SETUP, timerMinutes = NapRules.DEFAULT_MINUTES, notice = null)
        }
        announceSetup()
        applyNapCabin(saved)
    }

    fun openTimerSetup() {
        _state.update { it.copy(screen = Screen.TIMER_SETUP) }
        announceSetup()
    }

    fun adjustTimer(deltaMinutes: Int) = _state.update {
        it.copy(timerMinutes = (it.timerMinutes + deltaMinutes).coerceIn(NapRules.MIN_MINUTES, NapRules.MAX_MINUTES))
    }

    /** 타이머 조절 화면의 '완료': 정한 시간을 확인 화면으로 넘깁니다. */
    fun confirmTimer() {
        _state.update { it.copy(screen = Screen.TIMER_CONFIRM) }
        announceConfirm()
    }

    /** '파워냅 취소': 어느 화면에서든 파워냅을 끝내고 원상복귀한 뒤 세팅 화면으로 돌아갑니다. */
    fun cancelNap() = endSession(notice = "파워냅을 취소했어요.")

    fun startNap() {
        stopGuide()
        speech.stop()
        runTimer(_state.value.timerMinutes * SEC_PER_MIN)
    }

    fun extendNap() {
        napEndAt += napSec(NapRules.EXTEND_SEC) * 1000L
        _state.update { it.copy(napTotalSec = it.napTotalSec + napSec(NapRules.EXTEND_SEC)) }
    }

    fun wakeNow() {
        timerJob?.cancel()
        viewModelScope.launch { wakeUp(byAlarm = false) }
    }

    private fun runTimer(totalSec: Int) {
        timerJob?.cancel()
        napStartedAt = SystemClock.elapsedRealtime()
        napEndAt = napStartedAt + totalSec * 1000L
        _state.update { it.copy(screen = Screen.NAPPING, remainingSec = totalSec, napTotalSec = totalSec) }
        timerJob = viewModelScope.launch {
            while (true) {
                val left = secondsUntil(napEndAt)
                _state.update { it.copy(remainingSec = left) }
                if (left <= 0) break
                delay(250)
            }
            wakeUp(byAlarm = true)
        }
    }

    private suspend fun wakeUp(byAlarm: Boolean) {
        sessionNapSec += (SystemClock.elapsedRealtime() - napStartedAt) / 1000
        _state.update { it.copy(napElapsedMin = (sessionNapSec / SEC_PER_MIN.toFloat()).roundToInt()) }
        // 원상복귀는 뒤에서 진행하고, 알람과 퀴즈는 바로 이어갑니다.
        restoreCabinInBackground()
        if (byAlarm) {
            alarm.ring(settingsRepo.load()?.alarmSoundUri)
            speech.speak(NapRules.WAKE_TTS)
        }
        startQuizRound()
    }

    // ───────────── 각성 확인 퀴즈 ─────────────

    private fun startQuizRound() {
        stopGuide()
        retryJob?.cancel()
        quizJob?.cancel()
        quizJob = viewModelScope.launch {
            val quizzes = quizRepo.nextRound(NapRules.QUIZ_COUNT)
            val results = MutableList<Boolean?>(quizzes.size) { null }
            val reactionsMs = mutableListOf<Long>()

            _state.update {
                it.copy(
                    screen = Screen.QUIZ, quizIndex = 0, quizTotal = quizzes.size,
                    quizPhase = QuizPhase.INTRO, quizDisplay = quizzes.first().display,
                    quizChoices = quizzes.first().choices, quizResults = results.toList(),
                    heardText = "", listenSecLeft = 0,
                )
            }
            speech.speak(NapRules.INTRO_TTS)

            // 디자인(미통과 화면의 1번 ✓ 2번 ✗ 3번 ✓)처럼 3문제를 모두 낸 뒤 판정합니다.
            for ((index, quiz) in quizzes.withIndex()) {
                _state.update {
                    it.copy(
                        quizIndex = index, quizPhase = QuizPhase.ASKING,
                        quizDisplay = quiz.display, quizChoices = quiz.choices, heardText = "",
                    )
                }
                speech.speak(quiz.spoken)

                val (answer, reactionMs) = awaitAnswer()
                val correct = AnswerMatcher.isCorrect(answer, quiz)
                results[index] = correct
                if (correct) reactionsMs += reactionMs
                _state.update {
                    it.copy(
                        quizPhase = if (correct) QuizPhase.CORRECT else QuizPhase.WRONG,
                        heardText = answer.orEmpty(), quizResults = results.toList(),
                    )
                }
                if (correct) speech.speak(NapRules.CORRECT_TTS) else delay(1_200)
            }

            if (results.all { it == true }) {
                _state.update {
                    it.copy(screen = Screen.PASSED, avgReactionSec = reactionsMs.average().toFloat() / 1000f)
                }
                speech.speak(NapRules.PASSED_TTS)
            } else {
                showQuizFailed()
                speech.speak(NapRules.FAILED_TTS)
            }
        }
    }

    /**
     * 10초 동안 음성으로 답을 듣거나, 보기 버튼 터치를 기다립니다. 먼저 들어온 답을 씁니다.
     * @return 답(없으면 null)과 반응 시간(ms)
     */
    private suspend fun awaitAnswer(): Pair<String?, Long> = coroutineScope {
        val touch = CompletableDeferred<String>().also { touchAnswer = it }
        val startedAt = SystemClock.elapsedRealtime()
        val endAt = startedAt + NapRules.LISTEN_MS
        _state.update { it.copy(quizPhase = QuizPhase.LISTENING, listenSecLeft = 10) }

        val countdown = launch {
            while (true) {
                val left = secondsUntil(endAt)
                _state.update { it.copy(listenSecLeft = left) }
                if (left <= 0) break
                delay(200)
            }
        }
        val voice = async {
            speech.listen(NapRules.LISTEN_MS) { partial -> _state.update { it.copy(heardText = partial) } }
        }

        val answer = select<String?> {
            voice.onAwait { it }
            touch.onAwait { it }
        }
        val reactionMs = SystemClock.elapsedRealtime() - startedAt

        touchAnswer = null
        countdown.cancel()
        voice.cancel()
        speech.stop()
        answer to reactionMs
    }

    /** 디자인의 보기 버튼(5 / 7 / 9) */
    fun answerByTouch(choice: String) {
        if (_state.value.quizPhase == QuizPhase.LISTENING) touchAnswer?.complete(choice)
    }

    private fun showQuizFailed() {
        retryJob?.cancel()
        val endAt = SystemClock.elapsedRealtime() + napSec(NapRules.RETRY_SEC) * 1000L
        _state.update { it.copy(screen = Screen.QUIZ_FAILED, retrySecLeft = napSec(NapRules.RETRY_SEC)) }
        retryJob = viewModelScope.launch {
            while (true) {
                val left = secondsUntil(endAt)
                _state.update { it.copy(retrySecLeft = left) }
                if (left <= 0) break
                delay(250)
            }
            startQuizRound()   // 5분 뒤 자동 재출제
        }
    }

    /** '다시 확인' */
    fun retryQuizNow() {
        speech.stop()
        startQuizRound()
    }

    fun openStretching() {
        _state.update { it.copy(screen = Screen.STRETCHING) }
        startStretchGuide()
    }

    /** 1번부터 5번까지 차례로 읽어 주고, 동작마다 따라 할 시간을 기다립니다. */
    private fun startStretchGuide() {
        guideJob?.cancel()
        speech.stop()
        guideJob = viewModelScope.launch {
            try {
                speech.speak(NapRules.STRETCH_INTRO_TTS)
                for ((index, step) in NapRules.STRETCH_STEPS.withIndex()) {
                    _state.update { it.copy(stretchStep = index) }
                    speech.speak(step.spoken)
                    delay(step.holdSec * 1000L)
                }
                _state.update { it.copy(stretchStep = -1) }
                speech.speak(NapRules.STRETCH_DONE_TTS)
            } finally {
                // 중간에 화면을 벗어나도 강조 표시를 지웁니다.
                _state.update { it.copy(stretchStep = -1) }
            }
        }
    }

    /** 스트레칭 화면에서 돌아갈 때. 재출제 카운트다운은 계속 진행 중입니다. */
    fun closeStretching() {
        stopGuide()
        speech.stop()
        _state.update {
            if (it.screen == Screen.STRETCHING) it.copy(screen = Screen.QUIZ_FAILED) else it
        }
    }

    /** 퀴즈 대신 '10분 더 쉬기' */
    fun restMore() {
        quizJob?.cancel()
        speech.stop()
        settingsRepo.load()?.let { applyNapCabin(it) }
        runTimer(napSec(NapRules.REST_MORE_SEC))
    }

    /** 퀴즈 대신 '출발할게요' */
    fun departWithoutQuiz() = endSession(notice = "안전 운전하세요.")

    /** 통과 후 '출발하기' */
    fun depart() = endSession(notice = "안전 운전하세요.")

    // ───────────── 차량 제어 (뒤에서 순서대로 실행) ─────────────

    /**
     * 차량 작업을 뒤에서 실행합니다. 이전 작업이 있으면 취소하고 정리가 끝날 때까지 기다린 뒤 시작합니다.
     * (예: 시트가 눕는 중에 취소하면 → 시트 정지 → 원상복귀 순서로 진행)
     */
    private fun runCabinTask(task: suspend () -> Unit) {
        val previous = cabinJob
        cabinJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            task()
        }
    }

    /** 현재 상태를 기억해 두고 파워냅 환경을 적용합니다. 이미 적용 중이면 같은 값을 다시 맞출 뿐입니다. */
    private fun applyNapCabin(settings: NapSettings) = runCabinTask {
        if (snapshot == null) snapshot = vehicle.readCabin()
        vehicle.applyCabin(CabinMapper.toCabin(settings, _state.value.capabilities))
    }

    private fun restoreCabinInBackground() = runCabinTask { restoreCabin() }

    /** 시트·조명·온도를 파워냅 이전 상태로 되돌립니다. 여러 번 불러도 한 번만 복원합니다. */
    private suspend fun restoreCabin() {
        val previous = snapshot ?: return
        snapshot = null
        withContext(NonCancellable) { vehicle.applyCabin(previous) }
    }

    // ───────────── 화면 안내 음성 ─────────────

    /** 읽던 안내를 멈추고 새 안내를 읽습니다. */
    private fun announce(text: String) {
        guideJob?.cancel()
        guideJob = viewModelScope.launch { speech.speak(text) }
    }

    private fun stopGuide() {
        guideJob?.cancel()
        guideJob = null
    }

    private fun announceSetup() = announce(NapRules.SETUP_TTS)

    private fun announceConfirm() = announce(NapRules.confirmTts(_state.value.timerMinutes))

    // ───────────── 공통 ─────────────

    private fun onGearChanged(parked: Boolean) {
        val wasParked = _state.value.isParked
        val firstValue = !gearInitialized
        gearInitialized = true
        _state.update { it.copy(isParked = parked) }

        val s = _state.value
        if (parked && !wasParked) {
            android.util.Log.d("PowerNap", "P단 전환: 첫값=$firstValue 화면=${s.screen} 저장됨=${s.saved != null} 휴식단계=${autoStartAllowed()}")
        }
        when {
            // 쉼터 도착 후 P단으로 바꾸면 저장된 파워냅 환경을 자동 실행
            parked && !wasParked && !firstValue &&
                    s.screen == Screen.SETTINGS && s.saved != null && autoStartAllowed() -> startNapSession()

            // 파워냅 도중 P단을 벗어나면 안전을 위해 즉시 종료하고 원상복귀
            !parked && s.screen !in setOf(Screen.SETTINGS, Screen.ALARM_SOUND, Screen.PASSED) ->
                endSession(notice = "주행이 감지되어 파워냅을 종료했어요.")
        }
    }

    private fun endSession(notice: String?) {
        stopGuide()
        timerJob?.cancel()
        quizJob?.cancel()
        retryJob?.cancel()
        speech.stop()
        // 화면을 먼저 세팅으로 돌리고, 원상복귀는 뒤에서 진행합니다.
        _state.update { it.copy(screen = Screen.SETTINGS, notice = notice) }
        restoreCabinInBackground()
    }

    private inline fun updateDraft(block: (NapSettings) -> NapSettings) =
        _state.update { it.copy(draft = block(it.draft), notice = null) }

    private fun secondsUntil(endAt: Long) =
        ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

    override fun onCleared() {
        guideJob?.cancel()
        timerJob?.cancel()
        quizJob?.cancel()
        retryJob?.cancel()
        val pending = cabinJob
        // 화면이 사라져도 진행 중인 차량 작업을 정리한 뒤 원래 상태로 돌려놓고 연결을 해제합니다.
        CoroutineScope(Dispatchers.Main + NonCancellable).launch {
            pending?.cancelAndJoin()
            restoreCabin()
            vehicle.release()
            speech.release()
            alarm.release()
            gear.release()
        }
    }

    private companion object {
        const val TAG = "PowerNapVM"
    }
}