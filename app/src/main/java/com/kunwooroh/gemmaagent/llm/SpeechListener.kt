package com.kunwooroh.gemmaagent.llm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 폰에 들어 있는 한국어 음성인식으로 말을 글자로 바꾼다.
 * 가능하면 폰 안에서만 처리하는 온디바이스 인식기를 쓰고, 안 되면 일반 인식기로 자동 전환한다.
 * 반드시 메인 스레드에서 호출해야 한다.
 */
class SpeechListener(private val context: Context) {

  interface Callback {
    fun onPartial(text: String)
    fun onLevel(level: Float)
    fun onResult(text: String)
    /** 아무 말도 못 알아들었거나 오류. [fatal]이면 이 엔진을 쓸 수 없다. */
    fun onNothing(fatal: Boolean, message: String)
  }

  private var recognizer: SpeechRecognizer? = null
  private var useOnDevice = true
  private var callback: Callback? = null
  private var lastPartial = ""
  @Volatile var isListening = false
    private set

  val available: Boolean
    get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

  private fun onDeviceAvailable(): Boolean =
    try {
      SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (_: Throwable) {
      false
    }

  fun start(cb: Callback) {
    cancel()
    callback = cb
    lastPartial = ""
    val onDevice = useOnDevice && onDeviceAvailable()
    val r =
      try {
        if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
      } catch (e: Throwable) {
        null
      }
    if (r == null) {
      cb.onNothing(fatal = true, message = "이 폰에서 음성인식을 쓸 수 없어요")
      return
    }
    recognizer = r
    r.setRecognitionListener(
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
          callback?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
          callback?.onLevel(0f)
        }

        override fun onError(error: Int) {
          isListening = false
          val cbk = callback
          Log.w("SpeechListener", "error $error (onDevice=$onDevice)")
          // 온디바이스 인식기가 한국어를 못 하면 일반 인식기로 바꿔서 다시 시도
          if (onDevice && error in RETRY_WITH_DEFAULT) {
            useOnDevice = false
            if (cbk != null) start(cbk)
            return
          }
          release()
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
              if (lastPartial.isNotBlank()) cbk?.onResult(lastPartial)
              else cbk?.onNothing(fatal = false, message = "")
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
              cbk?.onNothing(fatal = false, message = "마이크 권한이 필요해요")
            SpeechRecognizer.ERROR_CLIENT -> cbk?.onNothing(fatal = false, message = "")
            else -> cbk?.onNothing(fatal = !onDevice && error == SpeechRecognizer.ERROR_SERVER, message = "음성인식 오류($error)")
          }
        }

        override fun onResults(results: Bundle?) {
          isListening = false
          val text =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
              ?: lastPartial
          release()
          if (text.isBlank()) callback?.onNothing(fatal = false, message = "") else callback?.onResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val text =
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
          if (!text.isNullOrBlank()) {
            lastPartial = text
            callback?.onPartial(text)
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
    )
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 말을 멈추고 잠깐 기다렸다가 끝낸다
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      }
    isListening = true
    r.startListening(intent)
  }

  /** 지금까지 들은 말로 끝내기 */
  fun stop() {
    recognizer?.stopListening()
  }

  fun cancel() {
    isListening = false
    try {
      recognizer?.cancel()
    } catch (_: Throwable) {}
    release()
  }

  private fun release() {
    try {
      recognizer?.destroy()
    } catch (_: Throwable) {}
    recognizer = null
  }

  companion object {
    private val RETRY_WITH_DEFAULT =
      setOf(
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        SpeechRecognizer.ERROR_SERVER,
      )
  }
}
