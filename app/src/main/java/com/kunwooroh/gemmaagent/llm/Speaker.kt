package com.kunwooroh.gemmaagent.llm

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 폰에 내장된 TTS로 한국어 음성 대답.
 * 모델이 글자를 만들어 내는 동안 문장이 끝날 때마다 바로 읽기 시작해서 기다림이 짧다.
 */
class Speaker(context: Context) {
  enum class Status { INITIALIZING, READY, NO_KOREAN, FAILED }

  private val _status = MutableStateFlow(Status.INITIALIZING)
  val status: StateFlow<Status> = _status

  private val _speaking = MutableStateFlow(false)
  val speaking: StateFlow<Boolean> = _speaking

  /** 대기열의 모든 문장을 다 읽었을 때 호출. */
  var onAllDone: (() -> Unit)? = null

  private val pending = AtomicInteger(0)
  private val seq = AtomicInteger(0)
  private val generation = AtomicInteger(0)

  private fun isCurrent(id: String?): Boolean =
    id != null && id.substringBefore('_').removePrefix("g") == generation.get().toString()
  private val buffer = StringBuilder()
  @Volatile private var finished = true

  var rate: Float = 1.05f
  var pitch: Float = 0.92f

  private lateinit var tts: TextToSpeech

  init {
    tts = TextToSpeech(context.applicationContext) { result -> onInit(result) }
  }

  private fun onInit(result: Int) {
      if (result != TextToSpeech.SUCCESS) {
        _status.value = Status.FAILED
        return
      }
      val r = tts.setLanguage(Locale.KOREAN)
      if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
        _status.value = Status.NO_KOREAN
        return
      }
      pickBestKoreanVoice()
      tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            if (isCurrent(utteranceId)) _speaking.value = true
          }

          override fun onDone(utteranceId: String?) {
            if (isCurrent(utteranceId)) onUtteranceEnd()
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            if (isCurrent(utteranceId)) onUtteranceEnd()
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            if (isCurrent(utteranceId)) onUtteranceEnd()
          }

          override fun onStop(utteranceId: String?, interrupted: Boolean) {}
        }
      )
      _status.value = Status.READY
    }

  /** 오프라인으로 쓸 수 있는 가장 품질 좋은 한국어 목소리를 고른다. */
  private fun pickBestKoreanVoice() {
    try {
      val voices: Set<Voice> = tts.voices ?: return
      val best =
        voices
          .filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
          .maxByOrNull { it.quality }
      if (best != null) tts.voice = best
    } catch (_: Throwable) {}
  }

  private fun onUtteranceEnd() {
    if (pending.decrementAndGet() <= 0) {
      pending.set(0)
      if (finished) {
        _speaking.value = false
        onAllDone?.invoke()
      }
    }
  }

  val isReady: Boolean
    get() = _status.value == Status.READY

  /** 새 대답을 시작한다 (이전에 읽던 것은 멈춤). */
  fun begin() {
    stop()
    synchronized(buffer) { buffer.clear() }
    finished = false
  }

  /** 스트리밍으로 들어온 글자 조각을 넣는다. 문장이 완성되면 바로 읽는다. */
  fun feed(chunk: String) {
    if (!isReady || chunk.isEmpty()) return
    val sentences = mutableListOf<String>()
    synchronized(buffer) {
      buffer.append(chunk)
      while (true) {
        val idx = findSentenceEnd(buffer)
        if (idx < 0) break
        sentences.add(buffer.substring(0, idx + 1))
        buffer.delete(0, idx + 1)
      }
    }
    sentences.forEach { enqueue(it) }
  }

  /** 대답이 끝났다. 남은 글자를 읽는다. 읽을 게 없으면 바로 onAllDone. */
  fun end() {
    val rest = synchronized(buffer) { buffer.toString().also { buffer.clear() } }
    finished = true
    if (rest.isNotBlank()) enqueue(rest)
    if (pending.get() <= 0) {
      _speaking.value = false
      onAllDone?.invoke()
    }
  }

  /** 한 번에 읽기 (브리핑, 다시 듣기). */
  fun speak(text: String) {
    begin()
    feed(text)
    end()
  }

  fun stop() {
    generation.incrementAndGet()
    finished = true
    pending.set(0)
    synchronized(buffer) { buffer.clear() }
    try {
      tts.stop()
    } catch (_: Throwable) {}
    _speaking.value = false
  }

  fun shutdown() {
    stop()
    tts.shutdown()
  }

  private fun enqueue(raw: String) {
    val text = cleanForSpeech(raw)
    if (text.isBlank()) return
    pending.incrementAndGet()
    tts.setSpeechRate(rate)
    tts.setPitch(pitch)
    val r = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "g${generation.get()}_${seq.incrementAndGet()}")
    if (r != TextToSpeech.SUCCESS) onUtteranceEnd()
  }

  private fun findSentenceEnd(sb: StringBuilder): Int {
    for (i in sb.indices) {
      val c = sb[i]
      if (c == '\n') return i
      if (c == '.' || c == '!' || c == '?' || c == '。' || c == '…') {
        // 소수점(3.5)은 문장 끝이 아님
        val prevDigit = i > 0 && sb[i - 1].isDigit()
        val nextDigit = i + 1 < sb.length && sb[i + 1].isDigit()
        if (prevDigit && (nextDigit || i + 1 >= sb.length)) continue
        if (i + 1 < sb.length || c != '.') return i
      }
    }
    return -1
  }

  companion object {
    private val EMOJI = Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")

    /** 마크다운 기호, 이모지 등 소리 내어 읽으면 어색한 것을 지운다. */
    fun cleanForSpeech(s: String): String =
      s.replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>|~]"), "")
        .replace(Regex("^\\s*[-•·]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(EMOJI, "")
        .replace("»", "")
        .replace(Regex("\\s+"), " ")
        .trim()
  }
}
