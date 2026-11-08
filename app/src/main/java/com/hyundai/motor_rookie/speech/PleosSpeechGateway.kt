package com.hyundai.motor_rookie.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode
// VERIFY: 리스너 인터페이스의 패키지와 이름 (각 SDK의 '이벤트 리스너' / '결과 리스너' 문서)
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener

/**
 * TextToSpeech / SpeechToText SDK 3.3.1 연동.
 *
 * 문서로 확인된 부분: 생성자(Context, Mode), initialize(), setLocale(),
 *   TTS addEventListener / speak / stop / removeEventListener / release,
 *   STT addListener / request / stop / removeListener / release.
 * VERIFY: 리스너 콜백 메서드 이름과 파라미터, speak() 파라미터.
 *
 * ON_DEVICE 모드를 쓰면 네트워크와 Playground 앱 등록 없이 동작합니다.
 * 인식 품질 때문에 HYBRID 로 바꾸려면 각 SDK의 '앱 등록' 절차를 추가하고,
 * TTS 는 OnServerConnectionListener.onConnected() 이후에 speak() 해야 합니다.
 */
class PleosSpeechGateway(context: Context) : SpeechGateway {

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)

    override suspend fun initialize() {
        tts.initialize()
        stt.initialize()
        tts.setLocale(Locale.KOREA)   // VERIFY: 파라미터 타입
        stt.setLocale(Locale.KOREA)   // VERIFY: 파라미터 타입
    }

    override suspend fun speak(text: String) {
        // 이벤트가 오지 않아도 흐름이 멈추지 않도록 넉넉한 상한을 둡니다.
        withTimeoutOrNull(text.length * 300L + 5_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : EventListener {
                    private fun finish() {
                        tts.removeEventListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    // 재생 완료
                    override fun onDone() = finish()

                    // stop()으로 중단됨
                    override fun onStop() = finish()

                    override fun onError(errorType: ErrorType) {
                        Log.w(TAG, "TTS 오류: $errorType")
                        finish()
                    }

                    override fun onReady() = Unit
                    override fun onStart() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                tts.addEventListener(listener)
                tts.speak(text)
                cont.invokeOnCancellation {
                    tts.stop()
                    tts.removeEventListener(listener)
                }
            }
        }
    }

    override suspend fun listen(windowMs: Long, onPartial: (String) -> Unit): String? {
        var latestPartial: String? = null

        val finalText = withTimeoutOrNull(windowMs) {
            suspendCancellableCoroutine<String?> { cont ->
                val listener = object : ResultListener {
                    private fun finish(result: String?) {
                        stt.removeListener(this)
                        if (cont.isActive) cont.resume(result)
                    }

                    // 인식 중간 결과와 최종 결과가 모두 여기로 옵니다.
                    override fun onUpdated(text: String, completed: Boolean) {
                        latestPartial = text
                        onPartial(text)
                        if (completed) finish(text)
                    }

                    // 최종 결과 없이 인식이 끝난 경우
                    override fun onEndedRecognition() = finish(latestPartial)

                    override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                        Log.w(TAG, "STT 오류: $errorType")
                        finish(latestPartial)
                    }

                    override fun onReady() = Unit
                    override fun onStartedRecognition() = Unit
                    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                stt.addListener(listener)
                stt.request()
                cont.invokeOnCancellation { stt.removeListener(listener) }
            }
        }

        stt.stop()
        return finalText ?: latestPartial
    }

    override fun stop() {
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    private companion object {
        const val TAG = "PleosSpeech"
    }
}
