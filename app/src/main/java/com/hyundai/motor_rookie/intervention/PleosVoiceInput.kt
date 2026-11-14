package com.hyundai.motor_rookie.intervention

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode

/**
 * Pleos SpeechToText SDK(ON_DEVICE)로 듣기 (VoiceInput 구현).
 *
 * 안드로이드 기본 음성 인식(AndroidStt)은 Pleos 에뮬레이터에 엔진이 없어 동작하지 않았지만,
 * 파워냅 기상 퀴즈에서 쓰는 Pleos STT 는 에뮬레이터에서도 인식된다.
 * 같은 방식(PleosSpeechGateway.listen)으로 1단계 퀴즈 · 쉼터 질문 · 비상 질문 · 음성 테스트에 쓴다.
 */
class PleosVoiceInput(context: Context) : VoiceInput {

    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    @Volatile private var ready = false

    init {
        try {
            stt.initialize()
            stt.setLocale(Locale.KOREA)
            ready = true
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos STT 초기화 실패", e)
        }
    }

    override val available: Boolean get() = ready

    override suspend fun listen(timeoutMs: Long): Heard {
        if (!ready) return Heard.Error("음성 인식을 시작할 수 없어요")
        var latest: String? = null
        var error: String? = null
        val text = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                val listener = object : ResultListener {
                    private fun finish(result: String?) {
                        stt.removeListener(this)
                        if (cont.isActive) cont.resume(result)
                    }
                    override fun onUpdated(text: String, completed: Boolean) {
                        latest = text
                        if (completed) finish(text)
                    }
                    override fun onEndedRecognition() = finish(latest)
                    override fun onError(errorType: SttErrorType) {
                        Log.w(TAG, "STT 오류: $errorType")
                        error = errorType.toString()
                        finish(latest)
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
        runCatching { stt.stop() }
        val result = (text ?: latest)?.trim()
        Log.i(TAG, "들은 말: ${result ?: "(없음)"}")
        return when {
            !result.isNullOrBlank() -> Heard.Text(result)
            error != null && latest == null -> Heard.Silence
            else -> Heard.Silence
        }
    }

    override fun shutdown() {
        runCatching { stt.stop() }
        runCatching { stt.release() }
    }

    private companion object { const val TAG = "GYEOL_STT" }
}
