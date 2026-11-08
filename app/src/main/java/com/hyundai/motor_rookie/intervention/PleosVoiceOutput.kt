package com.hyundai.motor_rookie.intervention

import android.media.AudioManager
import android.media.ToneGenerator
import com.hyundai.motor_rookie.alert.PleosSpeaker

/**
 * 퀴즈 음성 출력을 Pleos TextToSpeech SDK 로 바꿔 끼운 것 (VoiceOutput 구현).
 * AndroidTts 는 Pleos 에뮬레이터에서 엔진 연결이 안 돼(TTS 시작 실패 -1) 소리가 나지 않았다.
 * 단계 안내(LevelVoice)와 같은 PleosSpeaker 를 써서 두 음성이 겹치지 않는다.
 */
class PleosVoiceOutput(private val speaker: PleosSpeaker) : VoiceOutput {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()

    override suspend fun speak(text: String) = speaker.speak(text)

    override fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
    }

    /** 스피커는 MainActivity 가 정리한다 */
    override fun shutdown() {
        tone?.release()
    }
}
