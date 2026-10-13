package com.kunwooroh.gemmaagent.llm

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 마이크 녹음 → Gemma 4가 바로 이해하는 WAV(16kHz, 모노, 16bit)로 변환.
 * 별도의 음성인식(STT) 없이 Gemma 4가 소리를 직접 듣는다.
 */
class AudioRecorder {
  companion object {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 30
    const val SILENCE_MS = 1200L
    const val NO_SPEECH_MS = 8000L
  }

  @Volatile private var recording = false
  private var worker: Thread? = null
  private val pcm = ByteArrayOutputStream()

  /** 현재 녹음 레벨(0~1). 화면 애니메이션용. */
  @Volatile var level: Float = 0f
    private set

  val isRecording: Boolean
    get() = recording

  /**
   * @param detectSilence true면 말이 끝나고 잠깐 조용해지면 자동으로 멈춘다 (핸즈프리 대화용)
   * @param onAutoStop 자동으로 멈췄을 때 호출. 인자는 실제로 말소리가 있었는지 여부.
   */
  @SuppressLint("MissingPermission") // 호출 전에 RECORD_AUDIO 권한을 확인한다
  fun start(detectSilence: Boolean = false, onAutoStop: (Boolean) -> Unit) {
    if (recording) return
    pcm.reset()
    val minBuf =
      AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuf * 2,
      )
    recording = true
    recorder.startRecording()
    worker = thread(name = "audio-recorder") {
      val buf = ByteArray(minBuf)
      val maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS
      var autoStopped = false
      var heardSpeech = false
      var noiseFloor = 0.02f
      var lastLoud = System.currentTimeMillis()
      val startedAt = lastLoud
      try {
        while (recording) {
          val n = recorder.read(buf, 0, buf.size)
          if (n > 0) {
            synchronized(pcm) { pcm.write(buf, 0, n) }
            var peak = 0
            var i = 0
            while (i + 1 < n) {
              val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
              val a = kotlin.math.abs(s.toShort().toInt())
              if (a > peak) peak = a
              i += 2
            }
            level = (peak / 32768f).coerceIn(0f, 1f)
            if (pcm.size() >= maxBytes) {
              autoStopped = true
              break
            }
            if (detectSilence) {
              val now = System.currentTimeMillis()
              // 처음 0.3초로 주변 소음 크기를 잡는다
              if (now - startedAt < 300) noiseFloor = maxOf(noiseFloor, level * 0.9f)
              val threshold = maxOf(0.06f, minOf(noiseFloor * 2.5f, 0.2f))
              if (level > threshold) {
                if (now - startedAt > 150) heardSpeech = true
                lastLoud = now
              }
              // 말한 뒤 1.2초 조용하면 끝
              if (heardSpeech && now - lastLoud > SILENCE_MS) {
                autoStopped = true
                break
              }
              // 8초 동안 아무 말도 없으면 끝
              if (!heardSpeech && now - startedAt > NO_SPEECH_MS) {
                autoStopped = true
                break
              }
            }
          }
        }
      } finally {
        recording = false
        level = 0f
        try {
          recorder.stop()
        } catch (_: Throwable) {}
        recorder.release()
      }
      if (autoStopped) onAutoStop(heardSpeech || !detectSilence)
    }
  }

  /** 녹음을 끝내고 WAV 바이트를 돌려준다. 너무 짧으면 null. */
  fun stop(): ByteArray? {
    recording = false
    worker?.join(1000)
    worker = null
    val data = synchronized(pcm) { pcm.toByteArray() }
    if (data.size < SAMPLE_RATE * 2 / 2) return null // 0.5초 미만은 무시
    return toWav(data)
  }

  fun cancel() {
    recording = false
    worker?.join(1000)
    worker = null
    pcm.reset()
  }

  private fun toWav(pcmData: ByteArray): ByteArray {
    val channels = 1
    val bits = 16
    val byteRate = SAMPLE_RATE * channels * bits / 8
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + pcmData.size)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(channels.toShort())
        putInt(SAMPLE_RATE)
        putInt(byteRate)
        putShort((channels * bits / 8).toShort())
        putShort(bits.toShort())
        put("data".toByteArray())
        putInt(pcmData.size)
      }
    return header.array() + pcmData
  }
}
