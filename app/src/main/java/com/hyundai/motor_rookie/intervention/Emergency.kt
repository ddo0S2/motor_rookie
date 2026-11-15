package com.hyundai.motor_rookie.intervention

import android.Manifest
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ─────────────────────────────────────────────
// 비상 상황 안내·신고 (필수 기능)
//
//  트리거
//   - 단계별 개입에 3번 연속 무응답 → InterventionEvent.Emergency (MainActivity 가 start 호출)
//   - 급정거: 2초 안에 30km/h 이상 감속 (VehicleEmergencyMonitor)
//   - 연료 부족: 주행 가능 거리 50km 미만 또는 차량 연료 부족 경고 (VehicleEmergencyMonitor)
//
//  흐름 (비트박스 행동요령: 비상등 → 트렁크 → 대피 → 신고)
//   1) 비상등 켜기 → "비상등을 켰어요. 트렁크를 열고, 가드레일 밖으로 대피하세요"
//   2) "신고할까요?" → 응 → "다친 사람이 있나요?" → 응 119 / 아니 112
//                    → 아니 → "긴급견인을 부를까요?" → 응 1588-2504
//   3) 비상연락처가 있으면 "○○에게 전화할까요?"
//   화면 버튼(112/119, 긴급견인, 비상연락처, 괜찮아요)은 언제든 누를 수 있다.
//
//  시뮬레이션(설정 "버튼으로 대답 · 가짜 공조")
//   - 질문은 음성으로 읽고, 대답은 화면의 [응]/[아니요] 버튼
//   - 전화는 실제로 걸지 않고 "연결 중" 화면만 보여줌
// ─────────────────────────────────────────────

enum class EmergencyTrigger(val title: String, val spoken: String) {
    NO_RESPONSE("응답이 없어 비상등을 켰어요", "응답이 없어서 비상등을 켰어요."),
    HARD_BRAKE("급정거가 감지돼 비상등을 켰어요", "급정거가 감지돼서 비상등을 켰어요."),
    MANUAL("비상등을 켰어요", "비상등을 켰어요.")
}

enum class EmergencyPhase { GUIDE, CHOOSE_REPORT, CALLING }

data class CallTarget(val number: String, val label: String) {
    companion object {
        val POLICE = CallTarget("112", "경찰")
        val FIRE = CallTarget("119", "구급·화재")
        val TOW = CallTarget("1588-2504", "한국도로공사 긴급견인")
    }
}

data class EmergencyUi(
    val active: Boolean = false,
    val trigger: EmergencyTrigger = EmergencyTrigger.NO_RESPONSE,
    val hazardOn: Boolean = false,
    /** 지금 안내 중인 행동요령 단계 (1~4). 이보다 작은 단계는 완료 표시 */
    val step: Int = 1,
    val phase: EmergencyPhase = EmergencyPhase.GUIDE,
    /** 음성으로 묻고 있는 질문 (없으면 null) */
    val question: String? = null,
    val listening: Boolean = false,
    /** true = 화면 [응]/[아니요] 버튼으로 대답 */
    val manualAnswer: Boolean = false,
    val calling: CallTarget? = null,
    val callSimulated: Boolean = false,
    val contactName: String? = null,
    val message: String? = null
)

// ───────────────── 연료 부족 ─────────────────

data class FuelUi(
    val visible: Boolean = false,
    val rangeKm: Int = 0,
    val cardTitle: String = "가까운 주유소",
    val badge: String = "최단 경로",
    val primary: String = "",
    val primaryDetail: String = "",
    val next: String? = null,
    /** 경유지로 넣을 곳 (null 이면 내비에서 "주유소" 검색) */
    val target: RestStop? = null
)

// ───────────────── 매뉴얼 안내 ("지금 어떻게 해야 해?") ─────────────────

enum class ManualTopic(val label: String, val title: String, val keywords: List<String>, val steps: List<String>) {
    TIRE(
        "타이어 펑크", "타이어가 펑크 났을 때",
        listOf("펑크", "타이어", "바퀴"),
        listOf(
            "핸들을 꽉 잡고, 급브레이크 없이 천천히 속도를 줄이세요",
            "비상등을 켜고 갓길이나 안전한 곳으로 이동하세요",
            "트렁크를 열고 가드레일 밖으로 대피하세요",
            "긴급견인 1588-2504나 보험사 긴급출동을 부르세요"
        )
    ),
    WARNING(
        "경고등이 켜졌어요", "계기판 경고등이 켜졌을 때",
        listOf("경고등", "경고", "불이 들어", "계기판"),
        listOf(
            "경고등 색을 확인하세요. 빨간색이면 바로 멈춰야 해요",
            "비상등을 켜고 갓길이나 휴게소로 천천히 이동하세요",
            "안전한 곳에 세우고 시동을 끈 뒤 계기판 안내를 확인하세요",
            "계속 달리기 어려우면 긴급견인 1588-2504를 부르세요"
        )
    ),
    BREAKDOWN(
        "사고·고장으로 멈췄어요", "사고나 고장으로 멈췄을 때",
        listOf("사고", "고장", "멈췄", "시동"),
        listOf(
            "비상등을 켜세요",
            "트렁크를 열어 뒤차에 알리세요",
            "가드레일 밖 안전한 곳으로 대피하세요",
            "112나 119, 긴급견인 1588-2504에 신고하세요"
        )
    ),
    FIRE(
        "연기·불이 나요", "차에서 연기나 불이 날 때",
        listOf("불", "연기", "화재", "타는"),
        listOf(
            "바로 안전한 곳에 세우고 시동을 끄세요",
            "모두 차에서 내려 멀리 대피하세요",
            "119에 신고하세요",
            "불이 작을 때만 바람을 등지고 소화기를 쓰고, 크면 가까이 가지 마세요"
        )
    );

    companion object {
        fun match(text: String): ManualTopic? {
            val t = text.replace(" ", "")
            return values().firstOrNull { topic -> topic.keywords.any { t.contains(it.replace(" ", "")) } }
        }
    }
}

data class ManualUi(val visible: Boolean = false, val topic: ManualTopic? = null, val step: Int = -1)

// ───────────────── 비상등 ─────────────────

/** 비상등 제어. Pleos SDK 비상등 함수를 확인하면 실제 구현으로 바꿔 끼운다 */
interface HazardController {
    val isOn: Boolean
    /** 성공하면 true */
    suspend fun setHazard(on: Boolean): Boolean
}

class SimHazardController : HazardController {
    override var isOn = false
        private set
    override suspend fun setHazard(on: Boolean): Boolean { isOn = on; return true }
}

// ───────────────── 전화 ─────────────────

class PhoneCaller(private val context: Context) {
    /** 전화 앱으로 연결. 전화 앱이 없으면(에뮬레이터) false */
    fun call(number: String): Boolean {
        val uri = Uri.parse("tel:" + number.filter { it.isDigit() })
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "전화 앱 없음: $number"); false
        } catch (e: SecurityException) {
            Log.w(TAG, "전화 권한 없음: $number"); false
        }
    }

    companion object { private const val TAG = "GYEOL_SOS" }
}

// ───────────────── 비상 컨트롤러 ─────────────────

class EmergencyController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val voiceOut: VoiceOutput,
    private val realInput: VoiceInput,
    private val settings: () -> InterventionSettings,
    private val navigation: () -> NavigationController,
    private val simNavigation: NavigationController,
    private val simLocation: SimLocationSource,
    private val realLocation: LocationSource? = null,
    private val restRepo: RestRepository? = null,
    private val pleosNavi: PleosNavigationController? = null,
    private val hazard: HazardController = SimHazardController(),
    private val caller: PhoneCaller = PhoneCaller(context)
) {
    private val _ui = MutableStateFlow(EmergencyUi())
    val ui: StateFlow<EmergencyUi> = _ui.asStateFlow()
    private val _fuel = MutableStateFlow(FuelUi())
    val fuel: StateFlow<FuelUi> = _fuel.asStateFlow()
    private val _manual = MutableStateFlow(ManualUi())
    val manual: StateFlow<ManualUi> = _manual.asStateFlow()

    val active: Boolean get() = _ui.value.active

    private var flowJob: Job? = null
    private var manualJob: Job? = null
    private var pendingAnswer: CompletableDeferred<Boolean?>? = null
    private var callEnded: CompletableDeferred<Unit>? = null
    private var contact: EmergencyContact? = null
    private var lastFuelAlert = 0L

    private val sim get() = settings().simulation

    // ── 비상 모드 ──

    fun start(trigger: EmergencyTrigger) {
        if (_ui.value.active) return
        Log.i(TAG, "비상 모드 시작: $trigger")
        closeManual()
        _fuel.value = FuelUi()
        contact = EmergencyContactStore.load(context)
        _ui.value = EmergencyUi(active = true, trigger = trigger, contactName = contact?.name)
        flowJob = scope.launch { runFlow(trigger) }
    }

    private suspend fun runFlow(trigger: EmergencyTrigger) {
        // 1) 비상등
        val ok = runCatching { hazard.setHazard(true) }.getOrDefault(false)
        _ui.update { it.copy(hazardOn = ok, step = 2) }
        voiceOut.speak(trigger.spoken)
        // 2) 트렁크 → 3) 대피
        voiceOut.speak("트렁크를 열고,")
        _ui.update { it.copy(step = 3) }
        voiceOut.speak("가드레일 밖으로 대피하세요.")
        _ui.update { it.copy(step = 4) }

        // 4) 신고
        when (ask("신고할까요?")) {
            true -> {
                val target = when (ask("다친 사람이 있나요?")) {
                    true -> CallTarget.FIRE
                    false -> CallTarget.POLICE
                    null -> null
                }
                if (target == null) { chooseReport(); return }
                if (!call(target)) return
            }
            false -> if (ask("긴급견인을 부를까요?") == true) { if (!call(CallTarget.TOW)) return }
            null -> {}
        }

        // 5) 비상연락처
        val c = contact
        if (c != null && c.phone.isNotBlank()) {
            if (ask("${withEgeEge(c.name)} 전화할까요?") == true) call(CallTarget(c.phone, c.name))
        }
    }

    /** 예/아니요 질문. 대답이 없으면 null */
    private suspend fun ask(question: String): Boolean? {
        val manual = !settings().voiceAnswer || !realInput.available
        _ui.update { it.copy(question = question, manualAnswer = manual, listening = false) }
        voiceOut.speak(question)
        val answer: Boolean? = if (manual) {
            val d = CompletableDeferred<Boolean?>()
            pendingAnswer = d
            withTimeoutOrNull(30_000) { d.await() }
        } else {
            var result: Boolean? = null
            for (attempt in 0 until 2) {
                voiceOut.beep()
                _ui.update { it.copy(listening = true) }
                val heard = realInput.listen(6_000)
                _ui.update { it.copy(listening = false) }
                result = (heard as? Heard.Text)?.let { RestRepository.yesNo(it.text) }
                if (result != null) break
                if (attempt == 0) voiceOut.speak("다시 한 번 말해 주세요. $question")
            }
            result
        }
        pendingAnswer = null
        _ui.update { it.copy(question = null, listening = false) }
        Log.i(TAG, "질문 \"$question\" → $answer")
        return answer
    }

    /** 질문 막대의 [응]/[아니요] 버튼 */
    fun answer(yes: Boolean) { pendingAnswer?.complete(yes) }

    /** 전화 연결. 실제 전화 앱으로 넘어갔으면 false (음성 흐름은 여기서 멈춤) */
    private suspend fun call(target: CallTarget): Boolean {
        if (!sim && caller.call(target.number)) {
            _ui.update { it.copy(phase = EmergencyPhase.CALLING, calling = target, callSimulated = false) }
            Log.i(TAG, "전화 연결: ${target.number}")
            return false
        }
        // 시뮬레이션 (또는 전화 앱이 없는 에뮬레이터)
        _ui.update { it.copy(phase = EmergencyPhase.CALLING, calling = target, callSimulated = true) }
        val preset = target == CallTarget.POLICE || target == CallTarget.FIRE || target == CallTarget.TOW
        voiceOut.speak(if (preset) "${target.label}, ${target.number}에 연결할게요." else "${withEgeEge(target.label)} 전화할게요.")
        val d = CompletableDeferred<Unit>()
        callEnded = d
        d.await()
        callEnded = null
        _ui.update { it.copy(phase = EmergencyPhase.GUIDE, calling = null) }
        return true
    }

    /** [통화 끝] */
    fun endCall() {
        val d = callEnded
        if (d != null) d.complete(Unit)
        else _ui.update { it.copy(phase = EmergencyPhase.GUIDE, calling = null) }
    }

    // ── 화면 버튼 ──

    /** [112 / 119 신고] → 경찰 / 구급·화재 고르기 */
    fun onReportButton() {
        flowJob?.cancel()
        pendingAnswer = null
        flowJob = scope.launch { chooseReport() }
    }

    private suspend fun chooseReport() {
        _ui.update { it.copy(phase = EmergencyPhase.CHOOSE_REPORT, question = null) }
        voiceOut.speak("사고 신고는 112, 다친 사람이 있거나 불이 나면 119를 눌러 주세요.")
    }

    fun onCallButton(target: CallTarget) {
        flowJob?.cancel()
        pendingAnswer = null
        _ui.update { it.copy(question = null) }
        flowJob = scope.launch { call(target) }
    }

    fun onContactButton() {
        val c = contact ?: EmergencyContactStore.load(context).also { contact = it }
        if (c == null || c.phone.isBlank()) {
            _ui.update { it.copy(message = "등록된 비상연락처가 없어요. 처음 설정 화면에서 추가해 주세요.") }
            scope.launch { voiceOut.speak("등록된 비상연락처가 없어요.") }
            return
        }
        onCallButton(CallTarget(c.phone, c.name))
    }

    fun backToGuide() {
        _ui.update { it.copy(phase = EmergencyPhase.GUIDE) }
    }

    /** [괜찮아요, 취소] */
    fun cancel() {
        flowJob?.cancel()
        pendingAnswer = null
        callEnded = null
        _ui.value = EmergencyUi()
        scope.launch {
            runCatching { hazard.setHazard(false) }
            voiceOut.speak("비상 모드를 끝낼게요. 비상등을 껐어요.")
        }
        Log.i(TAG, "비상 모드 해제")
    }

    // ── 연료 부족 ──

    /** force = true: 시뮬레이션 버튼 (30분 제한 무시) */
    fun fuelLow(rangeKm: Int, force: Boolean = false) {
        if (_ui.value.active || _fuel.value.visible) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastFuelAlert != 0L && now - lastFuelAlert < 30 * 60_000L) return   // 30분에 한 번
        lastFuelAlert = now
        scope.launch {
            val fix = if (sim) simLocation.current() else realLocation?.current()
            val maxKm = (rangeKm * 0.8).coerceIn(5.0, 60.0)
            val areas = fix?.let { restRepo?.findAhead(it, maxKm = maxKm) }
                ?.filter { it.stop.kind == RestKind.REST_AREA }.orEmpty()
            val ui = when {
                // 고속도로: 갈 수 있는 거리 안의 다음 휴게소 주유소 우선
                areas.isNotEmpty() -> {
                    val first = areas[0]
                    FuelUi(
                        visible = true, rangeKm = rangeKm,
                        cardTitle = "다음 휴게소 주유소", badge = "고속도로",
                        primary = first.stop.displayName, primaryDetail = first.detail,
                        next = areas.getOrNull(1)?.let { "다음 휴게소 · ${it.stop.displayName} · ${"%.1f".format(it.distanceKm)} km" },
                        target = first.stop
                    )
                }
                // 일반 도로: 내비에서 주변 주유소 검색
                else -> FuelUi(
                    visible = true, rangeKm = rangeKm,
                    primary = "주변 주유소 찾기",
                    primaryDetail = "내비게이션에서 가까운 주유소를 검색해요",
                    next = null, target = null
                )
            }
            _fuel.value = ui
            voiceOut.speak("연료가 부족해요. 주행 가능 거리는 ${rangeKm}킬로미터예요. " +
                if (ui.target != null) "${withEuroRo(ui.primary)} 안내할까요?" else "가까운 주유소를 찾아 드릴까요?")
        }
    }

    fun fuelGuide() {
        val f = _fuel.value
        _fuel.value = FuelUi()
        scope.launch {
            val target = f.target
            if (target == null) {
                val p = pleosNavi?.takeIf { settings().useRealNavi }
                val err = if (p == null) "실제 내비가 꺼져 있어요" else p.searchNearby("주유소")
                if (err != null) Log.w(TAG, "주유소 검색 실패: $err")
                voiceOut.speak(if (err == null) "내비에서 주유소를 찾았어요." else "내비에 연결하지 못했어요.")
                return@launch
            }
            var nav = navigation()
            var err = nav.addWaypoint(target)
            if (err != null && nav !== simNavigation) { nav = simNavigation; err = nav.addWaypoint(target) }
            voiceOut.speak("${withEulReul(target.displayName)} ${nav.lastAction}.")
        }
    }

    fun closeFuel() { _fuel.value = FuelUi() }

    // ── 매뉴얼 안내 ──

    /** 음성 인식 결과가 들어오면 호출: "지금 어떻게 해야 해?", "타이어 펑크 났어" 등. 처리했으면 true */
    fun handleUtterance(text: String): Boolean {
        val topic = ManualTopic.match(text)
        val asking = listOf("어떻게", "뭐해야", "도와줘", "행동요령").any { text.replace(" ", "").contains(it) }
        if (topic == null && !asking) return false
        openManual(topic)
        return true
    }

    fun openManual(topic: ManualTopic? = null) {
        _manual.value = ManualUi(visible = true, topic = topic)
        if (topic != null) readManual(topic)
        else scope.launch { voiceOut.speak("지금 어떤 상황인가요?") }
    }

    fun readManual(topic: ManualTopic) {
        manualJob?.cancel()
        _manual.value = ManualUi(visible = true, topic = topic, step = -1)
        manualJob = scope.launch {
            voiceOut.speak("${topic.title} 이렇게 하세요.")
            topic.steps.forEachIndexed { i, s ->
                _manual.update { it.copy(step = i) }
                voiceOut.speak(s)
            }
            _manual.update { it.copy(step = topic.steps.size) }
        }
    }

    fun manualBackToTopics() {
        manualJob?.cancel()
        _manual.value = ManualUi(visible = true)
    }

    fun closeManual() {
        manualJob?.cancel()
        _manual.value = ManualUi()
    }

    fun shutdown() {
        flowJob?.cancel(); manualJob?.cancel()
    }

    companion object {
        private const val TAG = "GYEOL_SOS"

        private fun hasBatchim(s: String): Boolean {
            val c = s.trim().lastOrNull { it.isLetterOrDigit() } ?: return false
            if (c in '가'..'힣') return (c - '가') % 28 != 0
            return c in "0136781LMNR"          // 숫자·영문 끝소리 대충
        }
        private fun withEgeEge(name: String) = "${name}에게"
        private fun withEuroRo(s: String): String {
            val c = s.trim().lastOrNull() ?: return s
            val rieul = c in '가'..'힣' && (c - '가') % 28 == 8
            return if (hasBatchim(s) && !rieul) "${s}으로" else "${s}로"
        }
        private fun withEulReul(s: String) = if (hasBatchim(s)) "${s}을" else "${s}를"
    }
}

// ───────────────── 실제 차량 신호: 급정거 · 연료 ─────────────────

/**
 * 안드로이드 차량 표준 API(CarPropertyManager)로
 *  - 속도: 2초 안에 30km/h 이상 줄면 급정거 (40km/h 이상에서 출발했을 때만)
 *  - 연료: 주행 가능 거리(RANGE_REMAINING) 1~49km 이고 달리는 중일 때 (FUEL_LEVEL_LOW 는 기록만)
 * 권한: CAR_SPEED, CAR_ENERGY (없으면 그 부분만 조용히 꺼짐)
 */
class VehicleEmergencyMonitor(
    private val context: Context,
    private val onHardBrake: () -> Unit,
    private val onFuelLow: (rangeKm: Int) -> Unit
) {
    private var car: Car? = null
    private var pm: CarPropertyManager? = null
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var lastBrake = 0L
    @Volatile private var rangeKm = -1
    @Volatile private var speedKmh = 0f
    /** 주행 가능 거리가 부족한데 아직 출발 전이면, 달리기 시작할 때 한 번 알린다 */
    @Volatile private var fuelLowPending = false

    /**
     * 연료 부족 알림 조건
     *  - 주행 가능 거리가 1~49km 일 때만 (0 이하는 "값 없음"으로 보고 무시: 에뮬레이터는 0을 보내기도 함)
     *  - 실제로 달리는 중(5km/h 이상)일 때만. 앱을 켜자마자 정차 상태에서 뜨지 않게.
     */
    private fun checkFuel() {
        if (rangeKm !in 1 until 50) { fuelLowPending = false; return }
        if (speedKmh < 5f) { fuelLowPending = true; return }
        fuelLowPending = false
        onFuelLow(rangeKm)
    }

    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val kmh = (value.value as? Float ?: return) * 3.6f
            speedKmh = kmh
            if (fuelLowPending && kmh >= 5f) checkFuel()
            val now = SystemClock.elapsedRealtime()
            speeds.addLast(now to kmh)
            while (speeds.isNotEmpty() && now - speeds.first().first > 2_000) speeds.removeFirst()
            val maxRecent = speeds.maxOf { it.second }
            if (maxRecent >= 40f && maxRecent - kmh >= 30f && now - lastBrake > 60_000) {
                lastBrake = now
                speeds.clear()
                Log.i(TAG, "급정거 감지: ${maxRecent.toInt()} → ${kmh.toInt()} km/h")
                onHardBrake()
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {}
    }

    private val rangeCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val meters = value.value as? Float ?: return
            rangeKm = (meters / 1000f).toInt()
            Log.d(TAG, "주행 가능 거리 ${rangeKm}km")
            checkFuel()
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {}
    }

    private val lowCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // 에뮬레이터(전기차)는 이 경고를 계속 true 로 보내는 경우가 있어서 기록만 하고,
            // 알림은 주행 가능 거리(RANGE_REMAINING)로만 판단한다
            Log.d(TAG, "차량 연료 부족 경고: ${value.value}")
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {}
    }

    fun start() {
        try {
            val c = Car.createCar(context) ?: return
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm = m
            register(m, speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, "속도")
            register(m, rangeCb, VehiclePropertyIds.RANGE_REMAINING, "주행 가능 거리")
            register(m, lowCb, VehiclePropertyIds.FUEL_LEVEL_LOW, "연료 부족 경고")
        } catch (e: Exception) {
            Log.w(TAG, "차량 신호 연결 실패", e)
        }
    }

    private fun register(m: CarPropertyManager, cb: CarPropertyManager.CarPropertyEventCallback, id: Int, name: String) {
        try {
            val ok = m.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            Log.d(TAG, "$name 등록: $ok")
        } catch (e: SecurityException) {
            Log.w(TAG, "$name 권한 없음")
        } catch (e: Exception) {
            Log.w(TAG, "$name 등록 실패: ${e.message}")
        }
    }

    fun stop() {
        runCatching { pm?.unregisterCallback(speedCb) }
        runCatching { pm?.unregisterCallback(rangeCb) }
        runCatching { pm?.unregisterCallback(lowCb) }
        runCatching { car?.disconnect() }
    }

    companion object { private const val TAG = "GYEOL_SOS" }
}
