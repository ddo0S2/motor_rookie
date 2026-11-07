package com.hyundai.motor_rookie.alert

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import com.hyundai.motor_rookie.fatigue.FatigueLevel
import com.hyundai.motor_rookie.fatigue.FatigueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 피로 단계가 "올라갈 때" 한 번씩 음성으로 알려준다.
 *
 *   괜찮음 → 대화 : "지금 대화가 필요합니다"
 *   → 환경        : "지금 환기가 필요합니다"
 *   → 휴식        : "지금 휴식이 필요합니다"
 *
 * - 내려갈 때(예: 환경 → 대화)는 말하지 않는다. 같은 단계에 머무는 동안에도 반복하지 않는다.
 * - Pleos TextToSpeech SDK 를 쓴다. (Pleos 에뮬레이터에는 안드로이드 기본 TTS 엔진이 연결되지 않음)
 *   ON_DEVICE 모드 = 차 안에서 바로 음성을 만듦, 인터넷 필요 없음.
 * - 2·3번 팀원이 실제 대화·환기·휴식 안내를 붙이면, 이 안내 음성은 그 앞에 나오는 "예고" 역할.
 */
class LevelVoice(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var lastLevel = FatigueLevel.NORMAL
    private var job: Job? = null

    init {
        tts.initialize()
        tts.addEventListener(object : EventListener {
            override fun onReady() { Log.d(TAG, "TTS 준비") }
            override fun onStart() { Log.d(TAG, "읽기 시작") }
            override fun onDone() { Log.d(TAG, "읽기 끝") }
            override fun onStop() {}
            override fun onError(p0: ErrorType) { Log.e(TAG, "TTS 오류: $p0") }
            override fun onUpdatedRms(p0: Double) {}
        })
    }

    /** 문장은 여기만 고치면 된다 */
    fun message(level: FatigueLevel): String? = when (level) {
        FatigueLevel.NORMAL -> null
        FatigueLevel.TALK -> "지금 대화가 필요합니다"
        FatigueLevel.ENVIRONMENT -> "지금 환기가 필요합니다"
        FatigueLevel.REST -> "지금 휴식이 필요합니다"
    }

    /** 감지 모듈의 피로 상태를 지켜보다가 단계가 올라가면 말한다 */
    fun watch(fatigue: StateFlow<FatigueState>, scope: CoroutineScope) {
        job?.cancel()
        lastLevel = fatigue.value.level
        job = scope.launch {
            fatigue.collect { onLevel(it.level) }
        }
    }

    private fun onLevel(level: FatigueLevel) {
        if (level.ordinal > lastLevel.ordinal) {
            message(level)?.let { speak(it) }
        }
        lastLevel = level
    }

    /** 문장을 바로 읽는다 (확인용 화면의 "음성 테스트" 버튼도 이걸 부름) */
    fun speak(text: String) {
        Log.d(TAG, "말하기: $text")
        runCatching { tts.speak(text, true) }
            .onFailure { Log.e(TAG, "speak 실패", it) }
    }

    fun release() {
        job?.cancel()
        runCatching { tts.release() }
    }

    private companion object { const val TAG = "LevelVoice" }
}
