package com.hyundai.motor_rookie.alert

import com.hyundai.motor_rookie.fatigue.FatigueLevel
import com.hyundai.motor_rookie.fatigue.FatigueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * - 소리는 PleosSpeaker(앱 전체 공용 스피커)로 낸다. 퀴즈 음성과 겹치지 않고 차례로 나온다.
 * - 2번 파트(대화·공조)와 합친 뒤에는 MainActivity 가 단계가 오르기 직전에 announce() 를 불러
 *   이 안내가 퀴즈보다 먼저 나오게 한다.
 */
class LevelVoice(
    private val speaker: PleosSpeaker,
    private val scope: CoroutineScope,
) {
    private var lastLevel = FatigueLevel.NORMAL
    private var job: Job? = null

    /** 문장은 여기만 고치면 된다 */
    fun message(level: FatigueLevel): String? = when (level) {
        FatigueLevel.NORMAL -> null
        FatigueLevel.TALK -> "지금 대화가 필요합니다"
        FatigueLevel.ENVIRONMENT -> "지금 환기가 필요합니다"
        FatigueLevel.REST -> "지금 휴식이 필요합니다"
    }

    /** 단계가 이 값으로 올라간다고 알려 줄 때 (바로 줄을 서서 다른 음성보다 먼저 읽힌다) */
    fun announce(level: FatigueLevel) {
        message(level)?.let { speakFirst(it) }
    }

    /** (합치기 전 방식) 감지 모듈의 단계를 직접 지켜보다가 올라가면 말한다 */
    fun watch(fatigue: StateFlow<FatigueState>) {
        job?.cancel()
        lastLevel = fatigue.value.level
        job = scope.launch {
            fatigue.collect { s ->
                if (s.level.ordinal > lastLevel.ordinal) announce(s.level)
                lastLevel = s.level
            }
        }
    }

    /** 문장을 바로 읽는다 (설정의 "음성 테스트" 버튼도 이걸 부름) */
    fun speak(text: String) = speakFirst(text)

    // UNDISPATCHED: 지금 바로 스피커 줄에 서게 해서, 바로 뒤에 시작되는 퀴즈 음성보다 먼저 읽힌다
    private fun speakFirst(text: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { speaker.speak(text) }
    }

    fun stop() { job?.cancel() }
}
