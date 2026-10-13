package com.kunwooroh.gemmaagent.llm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 목소리 설정 */
data class VoiceConfig(
  /** "supertonic" 또는 "system" */
  val engine: String = ENGINE_SYSTEM,
  // Supertonic 3
  val stVoice: String = "F1",
  val stVoice2: String = "",
  val stBlend: Float = 0f,
  val stSpeed: Float = 1.05f,
  val stSteps: Int = 5,
  // 폰 기본 음성
  val sysEngine: String = "",
  val sysVoice: String = "",
  val sysRate: Float = 1.0f,
  val sysPitch: Float = 1.0f,
) {
  companion object {
    const val ENGINE_SUPERTONIC = "supertonic"
    const val ENGINE_SYSTEM = "system"
  }
}

/**
 * 대답을 소리로 읽는다. 엔진 두 가지:
 *  - Supertonic 3: 최신 무료 온디바이스 고음질 (여성 5 · 남성 5, 섞기 가능)
 *  - 폰 기본 TTS: 삼성·구글 등 폰에 깔린 음성 엔진과 그 목소리들
 * 모델이 글자를 만드는 동안 문장이 끝날 때마다 바로 합성해서, 앞 문장을 읽는 사이 다음 문장을 미리 만든다.
 */
class Speaker(context: Context) {
  enum class Status { INITIALIZING, READY, NO_KOREAN, FAILED }

  enum class StStatus { NOT_INSTALLED, LOADING, READY, FAILED }

  private val ctx = context.applicationContext
  val supertonicDir: File =
    (ctx.getExternalFilesDir("supertonic") ?: File(ctx.filesDir, "supertonic")).also { it.mkdirs() }

  private val _status = MutableStateFlow(Status.INITIALIZING)
  /** 폰 기본 TTS 상태 */
  val status: StateFlow<Status> = _status

  private val _stStatus =
    MutableStateFlow(if (Supertonic.isInstalled(supertonicDir)) StStatus.LOADING else StStatus.NOT_INSTALLED)
  val stStatus: StateFlow<StStatus> = _stStatus

  private val _speaking = MutableStateFlow(false)
  val speaking: StateFlow<Boolean> = _speaking

  /** 대기열의 모든 문장을 다 읽었을 때 호출. */
  var onAllDone: (() -> Unit)? = null

  @Volatile var config: VoiceConfig = VoiceConfig()
    private set

  // ── 상태 ──
  private val generation = AtomicInteger(0)
  private val outstanding = AtomicInteger(0)
  private val doneFired = AtomicBoolean(true)
  @Volatile private var finished = true
  private val buffer = StringBuilder()

  private data class Item(val gen: Int, val text: String)

  private data class Clip(val gen: Int, val text: String, val pcm: FloatArray?)

  private val textQueue = LinkedBlockingQueue<Item>()
  private val audioQueue = LinkedBlockingQueue<Clip>()

  // ── 엔진 ──
  private var tts: TextToSpeech? = null
  private var ttsEnginePkg: String = ""
  @Volatile private var supertonic: Supertonic? = null
  private val voiceCache = HashMap<String, Supertonic.Voice>()
  @Volatile private var currentTrack: AudioTrack? = null
  @Volatile private var utteranceLatch: CountDownLatch? = null
  @Volatile private var currentUtterance: String? = null

  init {
    createSystemTts("")
    thread(name = "tts-synth", isDaemon = true) { synthLoop() }
    thread(name = "tts-play", isDaemon = true) { playLoop() }
    if (_stStatus.value == StStatus.LOADING) loadSupertonicAsync()
  }

  // ───────────── 설정 ─────────────

  fun applyConfig(c: VoiceConfig) {
    val old = config
    config = c
    if (c.sysEngine != ttsEnginePkg) {
      createSystemTts(c.sysEngine)
    } else if (c.sysVoice != old.sysVoice) {
      applySystemVoice()
    }
    if (c.engine == VoiceConfig.ENGINE_SUPERTONIC && supertonic == null && Supertonic.isInstalled(supertonicDir)) {
      loadSupertonicAsync()
    }
  }

  /** 지금 설정으로 실제로 소리를 낼 수 있는지 */
  val isReady: Boolean
    get() = if (useSupertonic()) true else _status.value == Status.READY

  private fun useSupertonic() =
    config.engine == VoiceConfig.ENGINE_SUPERTONIC && supertonic != null && _stStatus.value == StStatus.READY

  // ───────────── 폰 기본 TTS ─────────────

  private fun createSystemTts(enginePkg: String) {
    try {
      tts?.shutdown()
    } catch (_: Throwable) {}
    _status.value = Status.INITIALIZING
    ttsEnginePkg = enginePkg
    val listener = TextToSpeech.OnInitListener { result -> onTtsInit(result) }
    tts = if (enginePkg.isBlank()) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, enginePkg)
  }

  private fun onTtsInit(result: Int) {
    val t = tts ?: return
    if (result != TextToSpeech.SUCCESS) {
      _status.value = Status.FAILED
      return
    }
    val r = t.setLanguage(Locale.KOREAN)
    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
      _status.value = Status.NO_KOREAN
      return
    }
    applySystemVoice()
    t.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) = release(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = release(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = release(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = release(utteranceId)

        private fun release(id: String?) {
          if (id != null && id == currentUtterance) utteranceLatch?.countDown()
        }
      }
    )
    _status.value = Status.READY
  }

  private fun applySystemVoice() {
    val t = tts ?: return
    try {
      val voices = t.voices ?: return
      val wanted = config.sysVoice
      val v =
        voices.firstOrNull { it.name == wanted }
          ?: voices.filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
      if (v != null) t.voice = v
    } catch (_: Throwable) {}
  }

  /** 설치된 TTS 엔진들 (패키지 이름, 표시 이름) */
  fun systemEngines(): List<Pair<String, String>> =
    try {
      tts?.engines?.map { it.name to it.label } ?: emptyList()
    } catch (_: Throwable) {
      emptyList()
    }

  /** 지금 엔진의 한국어 목소리들 */
  fun systemKoreanVoices(): List<Voice> =
    try {
      tts?.voices?.filter { it.locale.language == "ko" }?.sortedWith(
        compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name })
      ) ?: emptyList()
    } catch (_: Throwable) {
      emptyList()
    }

  // ───────────── Supertonic ─────────────

  private fun loadSupertonicAsync() {
    if (_stStatus.value == StStatus.READY && supertonic != null) return
    _stStatus.value = StStatus.LOADING
    thread(name = "supertonic-load", isDaemon = true) {
      try {
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        supertonic = Supertonic.load(supertonicDir, threads)
        _stStatus.value = StStatus.READY
      } catch (e: Throwable) {
        Log.e("Speaker", "supertonic load failed", e)
        _stStatus.value = StStatus.FAILED
      }
    }
  }

  /** 다운로드가 끝난 뒤 호출 */
  fun onSupertonicInstalled() {
    if (Supertonic.isInstalled(supertonicDir)) loadSupertonicAsync()
  }

  private fun voice(name: String): Supertonic.Voice? =
    synchronized(voiceCache) {
      voiceCache[name]
        ?: File(supertonicDir, "voice_styles/$name.json")
          .takeIf { it.exists() }
          ?.let { Supertonic.loadVoice(it) }
          ?.also { voiceCache[name] = it }
    }

  private fun currentStVoice(c: VoiceConfig): Supertonic.Voice? {
    val a = voice(c.stVoice) ?: voice("F1") ?: return null
    if (c.stVoice2.isBlank() || c.stBlend <= 0f) return a
    val b = voice(c.stVoice2) ?: return a
    return a.mix(b, c.stBlend)
  }

  // ───────────── 말하기 ─────────────

  /** 새 대답을 시작한다 (이전에 읽던 것은 멈춤). */
  fun begin() {
    stop()
    finished = false
    doneFired.set(false)
  }

  /** 스트리밍으로 들어온 글자 조각을 넣는다. 문장이 완성되면 바로 합성한다. */
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

  /** 대답이 끝났다. 남은 글자를 읽는다. */
  fun end() {
    val rest = synchronized(buffer) { buffer.toString().also { buffer.clear() } }
    if (rest.isNotBlank()) enqueue(rest)
    finished = true
    checkDone()
  }

  /** 한 번에 읽기 (브리핑, 다시 듣기, 미리 듣기). */
  fun speak(text: String) {
    begin()
    feed(text)
    end()
  }

  fun stop() {
    generation.incrementAndGet()
    finished = true
    doneFired.set(true)
    outstanding.set(0)
    textQueue.clear()
    audioQueue.clear()
    synchronized(buffer) { buffer.clear() }
    try {
      tts?.stop()
    } catch (_: Throwable) {}
    utteranceLatch?.countDown()
    currentTrack?.let {
      try {
        it.pause()
        it.flush()
      } catch (_: Throwable) {}
    }
    _speaking.value = false
  }

  fun shutdown() {
    stop()
    try {
      tts?.shutdown()
    } catch (_: Throwable) {}
    supertonic?.close()
  }

  private fun enqueue(raw: String) {
    val text = cleanForSpeech(raw)
    if (text.isBlank()) return
    outstanding.incrementAndGet()
    textQueue.put(Item(generation.get(), text))
  }

  private fun checkDone() {
    if (finished && outstanding.get() <= 0 && doneFired.compareAndSet(false, true)) {
      _speaking.value = false
      onAllDone?.invoke()
    }
  }

  private fun synthLoop() {
    while (true) {
      val item =
        try {
          textQueue.take()
        } catch (_: InterruptedException) {
          continue
        }
      if (item.gen != generation.get()) continue
      val c = config
      val pcm =
        if (useSupertonic()) {
          try {
            val v = currentStVoice(c)
            if (v != null) supertonic?.synthesize(item.text, v, "ko", c.stSteps.coerceIn(2, 16), c.stSpeed) else null
          } catch (e: Throwable) {
            Log.e("Speaker", "supertonic synth failed", e)
            null
          }
        } else null
      if (item.gen != generation.get()) continue
      audioQueue.put(Clip(item.gen, item.text, pcm))
    }
  }

  private fun playLoop() {
    while (true) {
      val clip =
        try {
          audioQueue.take()
        } catch (_: InterruptedException) {
          continue
        }
      if (clip.gen != generation.get()) continue
      _speaking.value = true
      try {
        if (clip.pcm != null) playPcm(clip.pcm, clip.gen) else playSystem(clip.text, clip.gen)
      } catch (e: Throwable) {
        Log.e("Speaker", "play failed", e)
      }
      if (clip.gen == generation.get()) {
        outstanding.decrementAndGet()
        checkDone()
      }
    }
  }

  private fun playSystem(text: String, gen: Int) {
    val t = tts ?: return
    if (_status.value != Status.READY) return
    val id = "g${gen}_${System.nanoTime()}"
    val latch = CountDownLatch(1)
    utteranceLatch = latch
    currentUtterance = id
    t.setSpeechRate(config.sysRate)
    t.setPitch(config.sysPitch)
    val r = t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
    if (r != TextToSpeech.SUCCESS) return
    latch.await(60, TimeUnit.SECONDS)
  }

  private fun playPcm(pcm: FloatArray, gen: Int) {
    val st = supertonic ?: return
    if (pcm.isEmpty()) return
    val rate = st.sampleRate
    val minBuf =
      AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(maxOf(minBuf, rate * 4 / 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    currentTrack = track
    try {
      track.play()
      var off = 0
      val step = rate / 10
      while (off < pcm.size && gen == generation.get()) {
        val n = minOf(step, pcm.size - off)
        val w = track.write(pcm, off, n, AudioTrack.WRITE_BLOCKING)
        if (w <= 0) break
        off += w
      }
      // 마지막까지 실제로 재생될 때까지 기다린다
      val deadline = System.currentTimeMillis() + 3000
      while (gen == generation.get() && track.playbackHeadPosition < off && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }
    } finally {
      currentTrack = null
      try {
        track.stop()
      } catch (_: Throwable) {}
      track.release()
    }
  }

  private fun findSentenceEnd(sb: StringBuilder): Int {
    for (i in sb.indices) {
      val c = sb[i]
      if (c == '\n') return i
      if (c == '.' || c == '!' || c == '?' || c == '。' || c == '…') {
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

    /** 목소리 이름 → 화면 표시 이름 */
    fun stVoiceLabel(name: String): String =
      when {
        name.startsWith("F") -> "여성 ${name.drop(1)}"
        name.startsWith("M") -> "남성 ${name.drop(1)}"
        else -> name
      }

    val ST_VOICES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
  }
}
