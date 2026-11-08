package com.hyundai.motor_rookie.alert

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱 전체가 함께 쓰는 Pleos 음성 출력 (TextToSpeech SDK, ON_DEVICE).
 *
 * - Pleos 에뮬레이터에는 안드로이드 기본 TTS 엔진이 연결되지 않아서, Pleos SDK 를 쓴다.
 * - 단계 안내(LevelVoice)와 퀴즈(InterventionManager)가 같은 스피커를 쓰기 때문에
 *   한 번에 한 문장만 읽도록 줄을 세운다(Mutex). 먼저 부른 쪽이 먼저 읽는다.
 * - speak() 는 다 읽을 때까지 기다렸다가 돌아온다 (퀴즈가 "읽고 → 듣기" 순서를 지킬 수 있게).
 */
class PleosSpeaker(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val queue = Mutex()
    @Volatile private var finished: CompletableDeferred<Unit>? = null

    init {
        tts.initialize()
        tts.addEventListener(object : EventListener {
            override fun onReady() { Log.d(TAG, "TTS 준비") }
            override fun onStart() { Log.d(TAG, "읽기 시작") }
            override fun onDone() { Log.d(TAG, "읽기 끝"); finished?.complete(Unit) }
            override fun onStop() { finished?.complete(Unit) }
            override fun onError(p0: ErrorType) { Log.e(TAG, "TTS 오류: $p0"); finished?.complete(Unit) }
            override fun onUpdatedRms(p0: Double) {}
        })
    }

    /** 문장을 읽고, 다 읽을 때까지 기다린다. 끝 신호가 안 오면 글자 수만큼만 기다리고 넘어간다. */
    suspend fun speak(text: String) = queue.withLock {
        Log.d(TAG, "말하기: $text")
        val done = CompletableDeferred<Unit>()
        finished = done
        val ok = runCatching { tts.speak(text, true) }
            .onFailure { Log.e(TAG, "speak 실패", it) }
            .isSuccess
        if (ok) withTimeoutOrNull(text.length * 250L + 4_000L) { done.await() }
        finished = null
    }

    fun release() {
        runCatching { tts.release() }
    }

    private companion object { const val TAG = "PleosSpeaker" }
}
