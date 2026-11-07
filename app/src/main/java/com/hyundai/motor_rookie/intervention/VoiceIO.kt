package com.hyundai.motor_rookie.intervention

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

// ─────────────────────────────────────────────
// 음성 입출력
// 지금은 안드로이드 기본 TTS/STT로 동작하고,
// Pleos SpeechToText / TextToSpeech SDK가 준비되면
// 같은 인터페이스를 구현한 클래스로 바꿔 끼우면 된다.
// ─────────────────────────────────────────────

interface VoiceOutput {
    suspend fun speak(text: String)
    /** 대답 전 "띵" 시작음 (PPT 3번 화면) */
    fun beep()
    fun shutdown()
}

sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Silence : Heard
    data class Error(val message: String) : Heard
}

interface VoiceInput {
    val available: Boolean
    suspend fun listen(timeoutMs: Long): Heard
    fun shutdown()
}

/** 안드로이드 기본 TTS (한국어) */
class AndroidTts(context: Context) : VoiceOutput {
    private val tag = "GYEOL_TTS"
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        Log.d(tag, "TTS 초기화 = ${status == TextToSpeech.SUCCESS}")
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private var languageSet = false
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()

    override suspend fun speak(text: String) {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (!ok) { delay(text.length * 90L); return }   // TTS 없으면 읽는 시간만큼만 대기
        if (!languageSet) {
            val r = tts.setLanguage(Locale.KOREAN)
            Log.d(tag, "한국어 설정 결과 = $r (음수면 한국어 음성 없음)")
            languageSet = true
        }

        val id = UUID.randomUUID().toString()
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.d(tag, "TTS 오류 = $errorCode")
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                cont.invokeOnCancellation { tts.stop() }
            }
        }
    }

    override fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
    }

    override fun shutdown() {
        tts.stop(); tts.shutdown(); tone?.release()
    }
}

/** 안드로이드 기본 음성 인식 (한국어). 반드시 메인 스레드에서 호출 */
class AndroidStt(private val context: Context) : VoiceInput {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val tag = "GYEOL_STT"

    override val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context).also {
            Log.d(tag, "음성 인식 사용 가능 = $it")
        }

    override suspend fun listen(timeoutMs: Long): Heard {
        if (!available) return Heard.Error("이 기기에서는 음성 인식을 쓸 수 없어요")
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Heard> { cont ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        Log.d(tag, "결과 = $text")
                        if (cont.isActive) cont.resume(if (text.isNullOrBlank()) Heard.Silence else Heard.Text(text))
                    }
                    override fun onError(error: Int) {
                        Log.d(tag, "onError = $error")
                        val heard = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Silence
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.Error("마이크 권한이 필요해요")
                            else -> Heard.Error("음성 인식 오류 ($error)")
                        }
                        if (cont.isActive) cont.resume(heard)
                    }
                    override fun onReadyForSpeech(params: Bundle?) { Log.d(tag, "듣기 준비 완료") }
                    override fun onBeginningOfSpeech() { Log.d(tag, "말 시작 감지") }
                    override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 2f) Log.d(tag, "소리 크기 $rmsdB") }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { Log.d(tag, "말 끝 감지") }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                Log.d(tag, "듣기 시작 요청")
                r.startListening(intent)
                cont.invokeOnCancellation { main.post { r.cancel() } }
            }
        }
        if (result == null) {
            Log.d(tag, "${timeoutMs / 1000}초 동안 결과 없음 (시간 초과)")
            main.post { r.cancel() }
        }
        return result ?: Heard.Silence
    }

    override fun shutdown() {
        recognizer?.destroy(); recognizer = null
    }
}

/**
 * 시뮬레이션 모드용 "버튼으로 대답하기".
 * 에뮬레이터에서 음성 인식이 안 될 때도 시연할 수 있게 해준다.
 */
class ManualVoiceInput : VoiceInput {
    override val available = true
    private var pending: CompletableDeferred<Heard>? = null

    override suspend fun listen(timeoutMs: Long): Heard {
        val d = CompletableDeferred<Heard>()
        pending = d
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: Heard.Silence
    }

    /** text = null 이면 무응답 */
    fun submit(text: String?) {
        pending?.complete(if (text == null) Heard.Silence else Heard.Text(text))
    }

    override fun shutdown() {}
}