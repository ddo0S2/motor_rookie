package com.hyundai.motor_rookie.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

enum class Screen {
    SETTINGS,        // 파워냅 세팅 (홈)
    NAP_READY,       // P단 전환 직후: 15/20분 고르기 + 세팅 전환 완료
    TIMER_SETUP,     // 1분 단위 조절 (±5분/±1분, 취소)
    TIMER_CONFIRM,   // 설정 시간 확인 + 시작
    NAPPING,         // 진행 중 (5분 연장 / 지금 깨우기)
    QUIZ,            // 각성 확인 퀴즈
    QUIZ_FAILED,     // 5분만 더 쉬어요
    STRETCHING,      // 스트레칭 안내
    PASSED,          // 출발해도 좋아요
}

enum class QuizPhase { INTRO, ASKING, LISTENING, CORRECT, WRONG }

data class UiState(
    val screen: Screen = Screen.SETTINGS,
    val isParked: Boolean = false,
    val capabilities: CabinCapabilities = CabinCapabilities(),
    val draft: NapSettings = NapSettings.DEFAULT,
    val saved: NapSettings? = null,
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
    val notice: String? = null,
)

object NapRules {
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 30
    val PRESETS = listOf(15, 20)
    const val EXTEND_SEC = 5 * 60
    const val REST_MORE_SEC = 10 * 60
    const val RETRY_SEC = 5 * 60
    const val QUIZ_COUNT = 3
    const val LISTEN_MS = 10_000L

    const val INTRO_TTS = "다시 출발할 준비 되셨나요? 간단한 질문 하나에 답해 주세요."
    const val WAKE_TTS = "일어날 시간이에요."
    const val CORRECT_TTS = "정답이에요."
    const val FAILED_TTS = "아직 반응이 조금 느려요. 5분만 더 쉬었다가 다시 확인할게요."
    const val PASSED_TTS = "출발해도 좋아요. 안전 운전하세요."
}

class PowerNapViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val quizRepo: QuizRepository = LocalQuizRepository()
    private val vehicle: VehicleGateway =
        if (USE_FAKE_VEHICLE) FakeVehicleGateway() else PleosVehicleGateway(app)
    private val speech: SpeechGateway = PleosSpeechGateway(app)
    private val gear = GearMonitor(app)
    private val alarm = AlarmPlayer()

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
    /** 차량 제어 작업(적용·복원). 화면 흐름과 분리해 뒤에서 순서대로 실행합니다. */
    private var cabinJob: Job? = null
    private var touchAnswer: CompletableDeferred<String>? = null
    private var gearInitialized = false

    init {
        viewModelScope.launch {
            runCatching { vehicle.connect() }.onFailure { Log.e(TAG, "Vehicle 연결 실패", it) }
            val caps = vehicle.capabilities()
            val saved = settingsRepo.load()
            _state.update { it.copy(capabilities = caps, saved = saved, draft = saved ?: NapSettings.DEFAULT) }
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

    // ───────────── 뒤로 가기 ─────────────

    /** 시스템 뒤로 가기. 설정 화면에서는 호출되지 않습니다(앱 종료는 시스템이 처리). */
    fun onBack() {
        when (_state.value.screen) {
            Screen.SETTINGS -> Unit
            Screen.NAP_READY, Screen.NAPPING -> cancelNap()
            Screen.TIMER_SETUP -> closeTimerSetup()
            Screen.TIMER_CONFIRM -> _state.update { it.copy(screen = Screen.TIMER_SETUP) }
            Screen.STRETCHING -> closeStretching()
            // 출발 전 안전 확인 단계는 뒤로 가기로 건너뛰지 않고, 화면의 버튼으로만 진행합니다.
            Screen.QUIZ, Screen.QUIZ_FAILED, Screen.PASSED -> Unit
        }
    }

    // ───────────── 파워냅 실행 ─────────────

    /** P단 전환 시 자동 호출. 이미 P단일 때는 상단 '바로 시작' 칩으로 호출합니다. */
    fun startNapSession() {
        val saved = settingsRepo.load() ?: return
        sessionNapSec = 0
        // 화면을 먼저 넘기고, 시트·조명·온도는 뒤에서 적용합니다.
        _state.update {
            it.copy(screen = Screen.NAP_READY, timerMinutes = NapRules.DEFAULT_MINUTES, notice = null)
        }
        applyNapCabin(saved)
    }

    fun selectPreset(minutes: Int) = _state.update { it.copy(timerMinutes = minutes) }

    fun openTimerSetup() = _state.update { it.copy(screen = Screen.TIMER_SETUP) }

    /** 타이머 조절 화면의 '취소': 조절을 그만두고 P단 직후 화면으로 돌아갑니다. */
    fun closeTimerSetup() = _state.update { it.copy(screen = Screen.NAP_READY) }

    fun adjustTimer(deltaMinutes: Int) = _state.update {
        it.copy(timerMinutes = (it.timerMinutes + deltaMinutes).coerceIn(NapRules.MIN_MINUTES, NapRules.MAX_MINUTES))
    }

    fun confirmTimer() = _state.update { it.copy(screen = Screen.TIMER_CONFIRM) }

    fun cancelNap() = endSession(notice = null)

    fun startNap() = runTimer(_state.value.timerMinutes * 60)

    fun extendNap() {
        napEndAt += NapRules.EXTEND_SEC * 1000L
        _state.update { it.copy(napTotalSec = it.napTotalSec + NapRules.EXTEND_SEC) }
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
        _state.update { it.copy(napElapsedMin = (sessionNapSec / 60f).roundToInt()) }
        // 원상복귀는 뒤에서 진행하고, 알람과 퀴즈는 바로 이어갑니다.
        restoreCabinInBackground()
        if (byAlarm) {
            alarm.ring()
            speech.speak(NapRules.WAKE_TTS)
        }
        startQuizRound()
    }

    // ───────────── 각성 확인 퀴즈 ─────────────

    private fun startQuizRound() {
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
        val endAt = SystemClock.elapsedRealtime() + NapRules.RETRY_SEC * 1000L
        _state.update { it.copy(screen = Screen.QUIZ_FAILED, retrySecLeft = NapRules.RETRY_SEC) }
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

    fun openStretching() = _state.update { it.copy(screen = Screen.STRETCHING) }

    /** 스트레칭 화면에서 돌아갈 때. 재출제 카운트다운은 계속 진행 중입니다. */
    fun closeStretching() = _state.update {
        if (it.screen == Screen.STRETCHING) it.copy(screen = Screen.QUIZ_FAILED) else it
    }

    /** 퀴즈 대신 '10분 더 쉬기' */
    fun restMore() {
        quizJob?.cancel()
        speech.stop()
        settingsRepo.load()?.let { applyNapCabin(it) }
        runTimer(NapRules.REST_MORE_SEC)
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

    /** 현재 상태를 기억해 두고 파워냅 환경을 적용합니다. */
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

    // ───────────── 공통 ─────────────

    private fun onGearChanged(parked: Boolean) {
        val wasParked = _state.value.isParked
        val firstValue = !gearInitialized
        gearInitialized = true
        _state.update { it.copy(isParked = parked) }

        val s = _state.value
        when {
            // 쉼터 도착 후 P단으로 바꾸면 저장된 파워냅 환경을 자동 실행
            parked && !wasParked && !firstValue &&
                    s.screen == Screen.SETTINGS && s.saved != null -> startNapSession()

            // 파워냅 도중 P단을 벗어나면 안전을 위해 즉시 종료하고 원상복귀
            !parked && s.screen != Screen.SETTINGS && s.screen != Screen.PASSED ->
                endSession(notice = "주행이 감지되어 파워냅을 종료했어요.")
        }
    }

    private fun endSession(notice: String?) {
        timerJob?.cancel()
        quizJob?.cancel()
        retryJob?.cancel()
        speech.stop()
        // 화면을 먼저 설정으로 돌리고, 원상복귀는 뒤에서 진행합니다.
        _state.update { it.copy(screen = Screen.SETTINGS, notice = notice) }
        restoreCabinInBackground()
    }

    private inline fun updateDraft(block: (NapSettings) -> NapSettings) =
        _state.update { it.copy(draft = block(it.draft), notice = null) }

    private fun secondsUntil(endAt: Long) =
        ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

    override fun onCleared() {
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
            gear.release()
        }
    }

    private companion object {
        const val TAG = "PowerNapVM"
    }
}