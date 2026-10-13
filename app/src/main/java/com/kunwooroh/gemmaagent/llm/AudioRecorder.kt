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
  }

  @Volatile private var recording = false
  private var worker: Thread? = null
  private val pcm = ByteArrayOutputStream()

  /** 현재 녹음 레벨(0~1). 화면 애니메이션용. */
  @Volatile var level: Float = 0f
    private set

  val isRecording: Boolean
    get() = recording

  @SuppressLint("MissingPermission") // 호출 전에 RECORD_AUDIO 권한을 확인한다
  fun start(onAutoStop: () -> Unit) {
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
      if (autoStopped) onAutoStop()
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
