package com.hyundai.motor_rookie.intervention

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 곁 - 1단계 대화 / 2단계 환경 개입 / 3단계 휴식 유도 담당.
 *
 * 입력: 감지 파트가 계산한 피로 점수 F → onFatigueScore(F)
 * 출력: ui(StateFlow) - 화면용 상태
 *       events(SharedFlow) - 퀴즈 결과(오답 점수, s_talk), 무응답 3회(비상)
 *
 * 규칙
 *  - F ≥ 40(+민감도) → 1단계: 들어오는 순간 퀴즈 3문제 한 번 (TTS → 시작음 → STT → 판정)
 *                      → 끝나면 메인 화면. 실제 주행에서만 1단계에 10분 넘게 머물면 한 번 더
 *  - F ≥ 60(+민감도) → 2단계: 온도 −2℃, 풍량 +1 한 번 (자동 퀴즈 없음, [말 걸어줘]는 언제든 가능)
 *  - F ≥ 75(+민감도) → 3단계: 창문 대각선 2개 조금 열기 + 가까운 졸음쉼터·휴게소 안내 → 경유지 추가
 *  - 올라갈 땐 바로, 내려갈 땐 임계값-10 아래로 일정 시간 유지돼야 내려감 (깜빡임 방지)
 *  - [괜찮아] → 20분 동안 1단계 대화 미루기
 *  - 무응답 3회 연속 → Emergency 이벤트 (비상 모드 파트로)
 */
class InterventionManager(
    private val scope: CoroutineScope,
    private val voiceOut: VoiceOutput,
    private val realInput: VoiceInput,
    val manualInput: ManualVoiceInput,
    realClimate: ClimateController,
    val simClimate: SimClimateController,
    private val settings: () -> InterventionSettings,
    // ── 3단계 ──
    private val realWindow: WindowController? = null,
    val simWindow: SimWindowController = SimWindowController(),
    private val realNavigation: NavigationController? = null,
    val simNavigation: SimNavigationController = SimNavigationController(),
    private val restRepo: RestRepository? = null,
    private val realLocation: LocationSource? = null,
    val simLocation: SimLocationSource = SimLocationSource(),
    private val isRaining: () -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val _ui = MutableStateFlow(InterventionUi())
    val ui: StateFlow<InterventionUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<InterventionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<InterventionEvent> = _events.asSharedFlow()

    private val climate = ClimateIntervention(
        controller = { if (settings().simulation) simClimate else realClimate }
    )

    private val windows = WindowIntervention(
        controller = { if (settings().simulation) simWindow else (realWindow ?: simWindow) }
    )
    private var restOptions: List<RestOption> = emptyList()
    private var restIndex = 0
    private var restJob: Job? = null

    private val commands = Channel<QuizCommand>(Channel.CONFLATED)
    private var quizJob: Job? = null
    private var lastQuizEnd = 0L
    private var belowSince: Long? = null
    private var consecutiveNoAnswer = 0
    private var pendingAnnouncement: String? = null
    private var bannerSeq = 0L
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    // 시뮬레이션에서는 시연이 빨리 진행되도록 시간을 줄인다
    private val sim get() = settings().simulation
    /** 음성으로 대답할지 (가짜 공조 여부와 따로 설정) */
    private val voice get() = settings().voiceAnswer && realInput.available
    private val downHoldMs get() = if (sim) 10_000L else 180_000L
    private val snoozeMs get() = if (sim) 60_000L else 20 * 60_000L

    /**
     * 1단계(대화)에 계속 머물 때 퀴즈를 다시 내는 간격.
     * 퀴즈는 "단계에 들어갈 때 한 번"이 기본이고, 1단계에 오래 머물 때만 이 간격으로 한 번 더 묻는다.
     * null = 다시 묻지 않음 (시뮬레이션: 단계별 흐름을 보기 쉽게)
     * 2·3단계에서는 자동으로 퀴즈를 내지 않는다 (각 단계의 개입만 실행).
     */
    private val talkRepeatMs: Long? get() = if (sim) null else 10 * 60_000L

    companion object {
        const val LISTEN_MS = 8_000L
        const val QUESTIONS_PER_SESSION = 3
        const val SLOW_ANSWER_MS = 5_000L
        const val TALK_THRESHOLD = 40
        const val CLIMATE_THRESHOLD = 60
        const val REST_THRESHOLD = 75
    }

    init {
        // 점수가 그대로여도 쿨다운/미루기 시간이 지나면 다시 판단하도록 주기 점검
        scope.launch {
            while (true) {
                delay(5_000)
                onFatigueScore(_ui.value.score)
            }
        }
    }

    // ───────────────── 피로 점수 입력 ─────────────────

    /** true 면 점수 판단·개입을 잠깐 멈춘다 (파워냅 중). MainActivity 가 켜고 끈다. */
    @Volatile var paused: Boolean = false
        set(value) {
            val was = field
            field = value
            // 파워냅이 시작되면 진행 중이던 퀴즈·쉼터 음성 질문을 끊는다 (파워냅 음성과 겹치지 않게)
            if (value && !was) {
                pendingAnnouncement = null
                quizJob?.cancel()
                restJob?.cancel()
                _ui.update { it.copy(restCard = RestCardUi()) }
            }
        }

    fun onFatigueScore(score: Int) {
        if (paused) return
        val s = settings()
        val t1 = TALK_THRESHOLD + s.sensitivity.offset
        val t2 = CLIMATE_THRESHOLD + s.sensitivity.offset
        val t3 = REST_THRESHOLD + s.sensitivity.offset
        val target = when {
            score >= t3 -> Stage.REST
            score >= t2 -> Stage.CLIMATE
            score >= t1 -> Stage.TALK
            else -> Stage.OBSERVE
        }
        val cur = _ui.value.stage

        val next = if (target.ordinal >= cur.ordinal) {
            belowSince = null; target
        } else {
            val curThreshold = when (cur) { Stage.REST -> t3; Stage.CLIMATE -> t2; else -> t1 }
            if (score < curThreshold - 10) {
                val since = belowSince ?: now().also { belowSince = it }
                if (now() - since >= downHoldMs) { belowSince = null; target } else cur
            } else { belowSince = null; cur }
        }

        _ui.update { it.copy(score = score, stage = next, status = statusText(score, next)) }
        if (next != cur) onStageChanged(cur, next)
        else maybeRepeatQuiz(next)
    }

    private fun statusText(score: Int, stage: Stage): String = when {
        _ui.value.quiz.visible -> "곁이 말을 걸고 있어요"
        stage == Stage.REST && _ui.value.windowOpened -> "창밖 공기로 환기 중이에요"
        stage == Stage.REST -> "쉬어 가는 게 좋겠어요"
        stage == Stage.CLIMATE && _ui.value.climateApplied -> "실내를 시원하게 바꿨어요"
        stage == Stage.CLIMATE -> "많이 피곤해 보여요"
        stage == Stage.TALK && now() < _ui.value.snoozedUntil -> "괜찮다고 하셔서 잠시 지켜볼게요"
        stage == Stage.TALK -> "조금 피곤해 보여요"
        score >= 30 -> "슬슬 피로가 쌓이고 있어요"
        else -> "지금은 괜찮아요"
    }

    private fun onStageChanged(from: Stage, to: Stage) {
        log("단계 변경: ${from.label} → ${to.label}")
        val up = to.ordinal > from.ordinal
        // 1단계에 "들어오는 순간" 퀴즈 한 번 (괜찮아로 미뤄 둔 동안은 제외)
        if (up && to == Stage.TALK && now() >= _ui.value.snoozedUntil) startQuiz()
        scope.launch {
            if (up) {
                // 2단계 이상으로 올라가면 공조 (0→3단계로 바로 뛰어도 공조부터)
                if (to.ordinal >= Stage.CLIMATE.ordinal && from.ordinal < Stage.CLIMATE.ordinal &&
                    settings().climateAuto && !climate.applied
                ) {
                    applyClimate()
                    if (to == Stage.REST) delay(3_000)   // 배너가 겹치지 않게
                }
                if (to == Stage.REST) enterRest()
            } else {
                if (from == Stage.REST) leaveRest()
                if (to.ordinal < Stage.CLIMATE.ordinal && climate.applied) {
                    climate.restore()?.let { showRestored(it, "졸음 지수가 내려가서 원래 설정으로 돌아왔어요") }
                    _ui.update { it.copy(climateApplied = false) }
                }
            }
        }
    }

    private suspend fun applyClimate() {
        val (ok, msg) = climate.apply()
        _ui.update { it.copy(climateApplied = climate.applied) }
        if (ok) showBanner(msg, "졸음 지수가 높아서 자동으로 조절했어요", BannerKind.CLIMATE, undoable = true)
        else showBanner(msg, "공조를 직접 조절해 주세요", BannerKind.ERROR, undoable = false)
        _ui.update { it.copy(status = statusText(it.score, it.stage)) }
        if (ok) announce("공기가 답답하실까 봐 에어컨 온도를 조금 낮췄어요.")
        log(msg)
    }

    // ───────────────── 3단계: 창문 + 쉼터 ─────────────────

    private suspend fun enterRest() {
        val s = settings()
        if (s.windowAuto) {
            val raining = if (sim) s.simRaining else isRaining()
            if (s.windowRainOff && raining) {
                showBanner("비가 와서 창문은 열지 않았어요", "에어컨으로 환기를 대신할게요", BannerKind.INFO, undoable = false)
                log("비 감지 → 창문 열기 생략")
            } else {
                val (ok, msg) = windows.open()
                _ui.update { it.copy(windowOpened = windows.opened) }
                if (ok) showBanner(msg, windows.seatText, BannerKind.WINDOW, undoable = true)
                else showBanner(msg, "창문을 직접 열어 주세요", BannerKind.ERROR, undoable = false)
                _ui.update { it.copy(status = statusText(it.score, it.stage)) }
                log(msg)
            }
            delay(2_500)
        }
        searchRest()
    }

    private suspend fun leaveRest() {
        windows.close()?.let { showRestored(it, "졸음 지수가 내려가서 창문을 닫았어요") }
        restJob?.cancel()
        _ui.update { it.copy(windowOpened = false, restCard = RestCardUi()) }
    }

    /** 쉼터 검색 → 카드 표시 → "경유지로 추가할까요?" */
    private suspend fun searchRest() {
        val repo = restRepo
        if (repo == null) {
            showBanner("쉼터 정보를 불러올 수 없어요", "쉼터 데이터가 연결되지 않았어요", BannerKind.ERROR, undoable = false)
            return
        }
        restJob?.cancel()
        _ui.update { it.copy(restCard = RestCardUi(visible = true, loading = true)) }

        val fix = (if (sim) simLocation.current() else realLocation?.current())
            ?: simLocation.current().also { log("위치를 못 받아 시뮬레이션 위치 사용") }
        restOptions = repo.findAhead(fix)
        restIndex = 0

        if (restOptions.isEmpty()) {
            _ui.update { it.copy(restCard = RestCardUi(visible = true, message = "근처에 쉼터를 찾지 못했어요", sample = repo.usingSample)) }
            announce("근처에서 쉼터를 찾지 못했어요.")
            log("쉼터 검색 결과 없음")
            return
        }
        showRestOption()
        val cur = restOptions[0]
        val next = restOptions.getOrNull(1)
        val say = buildString {
            append("이번 쉼터는 ${cur.stop.name}, ${km(cur.distanceKm)} 남았어요.")
            next?.let { append(" 다음 쉼터는 ${km(it.distanceKm)} 더 가야 해요.") }
            append(" 경유지로 추가할까요?")
        }
        log("쉼터 안내: ${cur.stop.displayName} ${cur.detail}")
        restJob = scope.launch { askRestByVoice(say) }
    }

    private suspend fun askRestByVoice(say: String) {
        while (quizJob?.isActive == true) delay(500)     // 퀴즈가 끝난 뒤에 말하기
        voiceOut.speak(say)
        if (!voice) return                                  // 버튼으로 대답
        voiceOut.beep()
        val heard = realInput.listen(6_000)
        if (heard is Heard.Text) when (RestRepository.yesNo(heard.text)) {
            true -> startRestGuide()
            false -> {
                _ui.update { it.copy(restCard = RestCardUi()) }   // dismissRest()는 이 작업 자체를 취소해서 직접 닫음
                voiceOut.speak("알겠어요. 필요하면 쉼터 찾기를 눌러 주세요.")
            }
            null -> {}
        }
    }

    private fun showRestOption() {
        _ui.update {
            it.copy(restCard = RestCardUi(
                visible = true,
                current = restOptions.getOrNull(restIndex),
                next = restOptions.getOrNull(restIndex + 1),
                sample = restRepo?.usingSample == true
            ))
        }
    }

    private fun km(d: Double) = "${"%.1f".format(d)}킬로미터"

    /** 1단계에 오래 머물 때만 talkRepeatMs 간격으로 한 번 더 묻는다 */
    private fun maybeRepeatQuiz(stage: Stage) {
        if (stage != Stage.TALK) return
        val repeat = talkRepeatMs ?: return
        if (quizJob?.isActive == true) return
        if (_ui.value.restCard.visible) return
        if (now() < _ui.value.snoozedUntil) return
        if (now() - lastQuizEnd < repeat) return
        startQuiz()
    }

    // ───────────────── 버튼에서 호출 ─────────────────

    /** 주행 화면 [말 걸어줘] */
    fun talkNow() = startQuiz()

    /** 퀴즈 화면 [괜찮아] / [다른 질문] / [그만] */
    fun sendCommand(c: QuizCommand) { commands.trySend(c) }

    /** 배너 [되돌리기] */
    fun undoClimate() {
        scope.launch {
            climate.restore()?.let { showRestored(it, "말씀하신 대로 바꾸기 전 설정으로 돌렸어요") }
            _ui.update { it.copy(climateApplied = false, status = statusText(it.score, it.stage)) }
        }
    }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    // ───────────────── 처음 설정: 음성 테스트 (PPT 1-1) ─────────────────

    val voiceInputAvailable: Boolean get() = realInput.available
    val realNaviConnected: Boolean get() = (realNavigation as? PleosNavigationController)?.connected == true
    val simulation: Boolean get() = sim

    /** "음성 테스트 중입니다. 말씀해 주세요." → 시작음 → 6초 듣기 */
    suspend fun runVoiceTest(): Heard {
        voiceOut.speak("음성 테스트 중입니다. 말씀해 주세요.")
        if (!realInput.available) return Heard.Error("이 기기에는 음성 인식 엔진이 없어요")
        voiceOut.beep()
        return realInput.listen(6_000)
    }

    /** 배너의 버튼: 공조 [되돌리기] / 창문 [닫기] */
    fun bannerAction(kind: BannerKind) {
        when (kind) {
            BannerKind.CLIMATE -> undoClimate()
            BannerKind.WINDOW -> closeWindows()
            else -> dismissBanner()
        }
    }

    /** 창문 배너 [닫기] */
    fun closeWindows() {
        scope.launch {
            windows.close()?.let { showRestored(it, "말씀하신 대로 창문을 닫았어요") }
            _ui.update { it.copy(windowOpened = false, status = statusText(it.score, it.stage)) }
        }
    }

    /** 주행 화면 [쉼터 찾기] */
    fun findRestNow() { scope.launch { searchRest() } }

    /** 쉼터 카드 [안내 시작] */
    fun startRestGuide() {
        scope.launch {
            val opt = restOptions.getOrNull(restIndex) ?: return@launch
            // 실제 Pleos 내비가 연결돼 있으면 시뮬레이션 모드여도 실제 내비로 보냄 (에뮬레이터 내비로 확인 가능)
            var nav = realNavigation?.takeIf { it.isReady && settings().useRealNavi } ?: simNavigation
            var err = nav.addWaypoint(opt.stop)
            if (err != null && nav !== simNavigation) {
                // 실제 내비 연결이 안 되면 가짜 내비로 시연은 계속
                log("실제 내비 실패($err) → 가짜 내비 사용")
                nav = simNavigation
                err = nav.addWaypoint(opt.stop)
            }
            restJob?.cancel()
            _ui.update { it.copy(restCard = RestCardUi()) }
            if (err == null) {
                val name = opt.stop.name
                val where = if (nav === simNavigation) "${nav.lastAction} (시뮬레이션)" else nav.lastAction
                showBanner("${withEulReul(name)} $where", opt.detail, BannerKind.RESTORED, undoable = false)
                announce("$name 쪽으로 안내할게요. 조금만 힘내요.")
                log("${if (nav === simNavigation) "가짜" else "Pleos"} 내비: ${opt.stop.displayName} ${nav.lastAction}")
            } else {
                showBanner("경유지를 추가하지 못했어요", err, BannerKind.ERROR, undoable = false)
                log(err)
            }
        }
    }

    /** 쉼터 카드 [다음 쉼터로] */
    fun nextRest() {
        if (restIndex + 1 >= restOptions.size) {
            showBanner("더 먼 쉼터는 찾지 못했어요", "지금 쉼터로 안내를 시작해 보세요", BannerKind.INFO, undoable = false)
            return
        }
        restIndex++
        showRestOption()
        val cur = restOptions[restIndex]
        restJob?.cancel()
        restJob = scope.launch { askRestByVoice("다음 쉼터는 ${cur.stop.name}, ${km(cur.distanceKm)} 남았어요. 여기로 갈까요?") }
    }

    /** 쉼터 카드 닫기 */
    fun dismissRest() {
        restJob?.cancel()
        _ui.update { it.copy(restCard = RestCardUi()) }
    }

    fun shutdown() {
        quizJob?.cancel(); restJob?.cancel(); voiceOut.shutdown(); realInput.shutdown()
    }

    // ───────────────── 1단계: 퀴즈 대화 ─────────────────

    private fun startQuiz() {
        if (quizJob?.isActive == true) return
        while (commands.tryReceive().isSuccess) { /* 이전 명령 비우기 */ }
        quizJob = scope.launch { runQuiz() }
    }

    private suspend fun runQuiz() {
        val s = settings()
        var input: VoiceInput = if (!voice) manualInput else realInput
        val asked = mutableListOf<Quiz>()
        val queue = ArrayDeque(QuizBank.pick(QUESTIONS_PER_SESSION, s.quizTypes))
        val results = mutableListOf<QuizResult>()
        var index = 0

        setQuiz {
            QuizUi(visible = true, total = queue.size, phase = QuizPhase.INTRO,
                question = "잠깐 저랑 이야기해요", feedbackSub = "곁이 말을 걸고 있어요", manualInput = input === manualInput)
        }
        log("1단계 대화 시작")

        try {
            if (handle(race { voiceOut.speak("잠깐 저랑 퀴즈 하나 해요. 소리가 나면 대답해 주세요.") })) return

            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                asked += q
                index++
                setQuiz {
                    it.copy(index = index, type = q.type, question = q.display,
                        expected = q.answers.first(), phase = QuizPhase.SPEAKING,
                        heard = "", feedback = "", feedbackSub = "", feedbackKind = FeedbackKind.NONE,
                        manualInput = input === manualInput)
                }

                // ① 질문 읽기
                if (handle(race { voiceOut.speak(q.prompt) })) return

                // ② 시작음 + 듣기
                voiceOut.beep()
                val started = now()
                setQuiz { it.copy(phase = QuizPhase.LISTENING, listenStartedAt = started, listenMs = LISTEN_MS) }
                val raced = race { input.listen(LISTEN_MS) }
                val elapsed = now() - started

                // ③ 버튼 명령
                if (raced is QuizCommand) {
                    if (raced == QuizCommand.SKIP) {
                        QuizBank.pickOne(s.quizTypes, asked)?.let { queue.addFirst(it) }
                        index--
                        continue
                    }
                    handle(raced); return
                }

                val heard = raced as Heard
                var feedback: String
                var feedbackSub = "다음 문제로 넘어갈게요"
                var kind: FeedbackKind
                when (heard) {
                    is Heard.Text -> {
                        val correct = QuizBank.isCorrect(q, heard.text)
                        if (!correct) {
                            // 오답처럼 보여도 "괜찮아/다른 질문/그만"이면 명령으로 처리
                            when (val cmd = QuizBank.command(heard.text)) {
                                QuizCommand.SKIP -> {
                                    QuizBank.pickOne(s.quizTypes, asked)?.let { queue.addFirst(it) }
                                    index--; continue
                                }
                                null -> {}
                                else -> { handle(cmd); return }
                            }
                        }
                        consecutiveNoAnswer = 0
                        results += QuizResult(q, heard.text, correct, noAnswer = false, responseMs = elapsed)
                        if (correct) {
                            kind = FeedbackKind.CORRECT
                            feedback = "정답이에요! ${q.answerText}"
                            feedbackSub = "들은 말: “${heard.text}”"
                        } else {
                            kind = FeedbackKind.WRONG
                            feedback = if (q.type == QuizType.REPEAT) "조금 달랐어요"
                            else "아쉬워요, 정답은 ${withIeyo(q.answerText)}"
                        }
                        setQuiz { it.copy(heard = heard.text) }
                    }
                    is Heard.Error -> {
                        // 음성 인식이 안 되면 버튼 대답으로 바꾸고 같은 문제를 다시
                        log(heard.message)
                        input = manualInput
                        queue.addFirst(q); asked.remove(q); index--
                        setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = heard.message,
                            feedbackSub = "화면 버튼으로 대답해 주세요", feedbackKind = FeedbackKind.INFO, manualInput = true) }
                        delay(1_500)
                        continue
                    }
                    Heard.Silence -> {
                        consecutiveNoAnswer++
                        results += QuizResult(q, null, correct = false, noAnswer = true, responseMs = elapsed)
                        kind = FeedbackKind.NO_ANSWER
                        feedback = "대답이 없어요"
                        log("무응답 ${consecutiveNoAnswer}회 연속")
                        if (consecutiveNoAnswer >= 3) {
                            _events.tryEmit(InterventionEvent.Emergency)
                            consecutiveNoAnswer = 0
                            log("무응답 3회 → 비상 모드 요청")
                            setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = "대답이 없어요",
                                feedbackSub = "비상 모드로 전환할게요", feedbackKind = FeedbackKind.NO_ANSWER) }
                            voiceOut.speak("대답이 없어서 비상 모드로 전환할게요.")
                            return
                        }
                    }
                }

                // ④ 피드백
                setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = feedback,
                    feedbackSub = feedbackSub, feedbackKind = kind) }
                val spoken = if (kind == FeedbackKind.NO_ANSWER) "대답이 없어서 다음으로 넘어갈게요." else feedback
                if (handle(race { voiceOut.speak(spoken) })) return
            }

            // 세션 종료: 결과 요약 → 다른 파트로 전달
            finishSession(results)
        } finally {
            lastQuizEnd = now()
            setQuiz { QuizUi(visible = false) }
            _ui.update { it.copy(status = statusText(it.score, it.stage)) }
            pendingAnnouncement?.let { pendingAnnouncement = null; scope.launch { voiceOut.speak(it) } }
        }
    }

    private suspend fun finishSession(results: List<QuizResult>) {
        val wrong = results.count { !it.correct && !it.noAnswer }
        val noAnswer = results.count { it.noAnswer }
        val slow = results.count { it.correct && it.responseMs > SLOW_ANSWER_MS }
        val correct = results.count { it.correct }

        // 기획서 5.3 "한 문제 틀릴 때마다 졸음 점수 부여"
        val penalty = wrong * 5 + noAnswer * 10
        // s_talk (0~1): 오답 1, 무응답 2, 느린 정답 0.5 가중
        val talk = ((wrong * 1f + noAnswer * 2f + slow * 0.5f) / (results.size.coerceAtLeast(1) * 2f))
            .coerceIn(0f, 1f)

        _ui.update { it.copy(talkSignal = talk) }
        _events.tryEmit(InterventionEvent.QuizFinished(results, penalty, talk))
        log("퀴즈 종료: 정답 $correct / 오답 $wrong / 무응답 $noAnswer → 졸음 점수 +$penalty, s_talk ${"%.2f".format(talk)}")

        val summary = when {
            correct == results.size -> "모두 맞히셨어요!"
            correct == 0 -> "많이 피곤하신 것 같아요"
            else -> "${results.size}문제 중 ${correct}문제 맞혔어요"
        }
        setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = summary,
            feedbackSub = "정답 $correct · 오답 $wrong · 무응답 $noAnswer",
            feedbackKind = if (correct == results.size) FeedbackKind.CORRECT else FeedbackKind.INFO) }
        voiceOut.speak(summary)
    }

    /** 말하기/듣기 도중 버튼 명령이 오면 바로 끊고 명령을 돌려준다 */
    private suspend fun race(block: suspend () -> Any): Any = coroutineScope {
        val work = async { block() }
        select<Any> {
            work.onAwait { it }
            commands.onReceive { cmd -> work.cancel(); cmd }
        }
    }

    /** 명령이면 처리 후 true(세션 종료), 아니면 false */
    private suspend fun handle(result: Any?): Boolean {
        if (result !is QuizCommand) return false
        when (result) {
            QuizCommand.SNOOZE -> {
                val until = now() + snoozeMs
                _ui.update { it.copy(snoozedUntil = until) }
                _events.tryEmit(InterventionEvent.Snoozed((snoozeMs / 60_000).toInt()))
                log("괜찮아 → ${snoozeMs / 60_000}분 동안 대화 미루기")
                setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = "알겠어요",
                    feedbackSub = "조금 있다 다시 말 걸게요", feedbackKind = FeedbackKind.INFO) }
                voiceOut.speak("알겠어요. 조금 있다가 다시 말 걸게요.")
            }
            QuizCommand.STOP -> {
                log("그만 → 대화 종료")
                setQuiz { it.copy(phase = QuizPhase.FEEDBACK, feedback = "대화를 마칠게요",
                    feedbackSub = "필요하면 [말 걸어줘]를 눌러 주세요", feedbackKind = FeedbackKind.INFO) }
                voiceOut.speak("알겠어요. 필요하면 말 걸어줘 버튼을 눌러주세요.")
            }
            QuizCommand.SKIP -> return false
        }
        return true
    }

    // ───────────────── 공통 ─────────────────

    /** 퀴즈 중이면 끝난 뒤에 말하고, 아니면 바로 말한다 (음성 겹침 방지) */
    private fun announce(text: String) {
        if (quizJob?.isActive == true) pendingAnnouncement = text
        else scope.launch { voiceOut.speak(text) }
    }

    private fun showRestored(text: String, sub: String) {
        showBanner(text, sub, BannerKind.RESTORED, undoable = false)
        log(text)
    }

    private fun hasBatchim(word: String): Boolean {
        val last = word.lastOrNull { it in '가'..'힣' || it in '0'..'9' } ?: return false
        return when (last) {
            in '가'..'힣' -> (last - '가') % 28 != 0
            else -> last in "013678"
        }
    }

    /** "안성휴게소" → "안성휴게소를", "졸음쉼터" → "졸음쉼터를", "망향" → "망향을" */
    private fun withEulReul(word: String) = word + if (hasBatchim(word)) "을" else "를"

    /** "7일" → "7일이에요", "빨간색" → "빨간색이에요", "7가지" → "7가지예요" */
    private fun withIeyo(word: String): String {
        val last = word.lastOrNull() ?: return word
        val hasBatchim = when (last) {
            in '가'..'힣' -> (last - '가') % 28 != 0
            in '0'..'9' -> last in "013678"   // 영 일 삼 육 칠 팔
            else -> false
        }
        return word + if (hasBatchim) "이에요" else "예요"
    }

    private fun showBanner(text: String, sub: String, kind: BannerKind, undoable: Boolean) {
        val id = ++bannerSeq
        _ui.update { it.copy(banner = Banner(id, text, sub, kind, undoable)) }
        scope.launch {
            delay(8_000)   // 짧게 떴다 사라지는 배너
            _ui.update { if (it.banner?.id == id) it.copy(banner = null) else it }
        }
    }

    private inline fun setQuiz(crossinline f: (QuizUi) -> QuizUi) =
        _ui.update { it.copy(quiz = f(it.quiz)) }

    private fun log(msg: String) =
        _ui.update { it.copy(log = (listOf("${timeFmt.format(Date(now()))}  $msg") + it.log).take(30)) }
}
