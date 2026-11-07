package com.hyundai.motor_rookie.speech

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * 앱의 나머지 코드는 이 인터페이스만 사용합니다.
 * TTS/STT SDK 메서드 이름이 다르면 PleosSpeechGateway 한 파일만 고치면 됩니다.
 */
interface SpeechGateway {
    suspend fun initialize()

    /** 문장을 읽고, 다 읽을 때까지 기다립니다. */
    suspend fun speak(text: String)

    /**
     * windowMs 동안 음성을 인식합니다.
     * @return 최종 인식 문장. 시간 안에 최종 결과가 없으면 마지막 중간 결과, 그것도 없으면 null.
     */
    suspend fun listen(windowMs: Long, onPartial: (String) -> Unit = {}): String?

    /** 재생과 인식을 즉시 멈춥니다. */
    fun stop()

    fun release()
}

/** 파워냅 종료 알람음 */
class AlarmPlayer {

    suspend fun ring(durationMs: Long = 4_000L) {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        try {
            repeat((durationMs / BEAT_MS).toInt()) {
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                delay(BEAT_MS)
            }
        } finally {
            tone.release()
        }
    }

    private companion object {
        const val BEAT_MS = 800L
    }
}
