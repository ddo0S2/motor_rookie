package com.hyundai.motor_rookie.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
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

/**
 * 파워냅 종료 알람과 알람음 미리듣기.
 * 알람은 '선택한 소리 → 기기 기본 알람음 → 삐 소리' 순서로 시도해서, 어떤 차량에서도 반드시 울리게 합니다.
 */
class AlarmPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var previewPlayer: MediaPlayer? = null

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 작게 시작해서 점점 커지며 durationMs 동안 울립니다. */
    suspend fun ring(soundUri: String?, durationMs: Long = RING_MS) {
        stopPreview()
        val candidates = listOfNotNull(
            soundUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        val player = candidates.firstNotNullOfOrNull { createPlayer(it, looping = true) }
        if (player == null) {
            beep(durationMs)
            return
        }
        try {
            player.setVolume(MIN_VOLUME, MIN_VOLUME)
            player.start()
            val totalSteps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            val rampSteps = (RAMP_MS / STEP_MS).toInt().coerceAtLeast(1)
            repeat(totalSteps) { step ->
                val volume = (MIN_VOLUME + (1f - MIN_VOLUME) * step / rampSteps).coerceAtMost(1f)
                player.setVolume(volume, volume)
                delay(STEP_MS)
            }
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    /** 세팅 화면에서 소리를 고를 때 한 번 들려줍니다. 다른 소리를 고르면 이전 미리듣기는 멈춥니다. */
    fun preview(soundUri: String) {
        stopPreview()
        val player = createPlayer(Uri.parse(soundUri), looping = false) ?: return
        player.setOnCompletionListener { if (previewPlayer === player) stopPreview() }
        previewPlayer = player
        player.start()
    }

    fun stopPreview() {
        previewPlayer?.let {
            runCatching { it.stop() }
            it.release()
        }
        previewPlayer = null
    }

    fun release() = stopPreview()

    private fun createPlayer(uri: Uri, looping: Boolean): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(alarmAttributes)
            player.setDataSource(appContext, uri)
            player.isLooping = looping
            player.prepare()
            player
        } catch (e: Exception) {
            Log.w(TAG, "알람음 준비 실패: $uri", e)
            player.release()
            null
        }
    }

    /** 알람음을 하나도 쓸 수 없을 때의 마지막 대안 */
    private suspend fun beep(durationMs: Long) {
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
        const val TAG = "AlarmPlayer"
        const val RING_MS = 8_000L
        const val RAMP_MS = 5_000L
        const val STEP_MS = 200L
        const val MIN_VOLUME = 0.3f
        const val BEAT_MS = 800L
    }
}