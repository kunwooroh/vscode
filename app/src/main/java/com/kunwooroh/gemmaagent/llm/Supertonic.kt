package com.kunwooroh.gemmaagent.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random

/**
 * Supertonic 3 (Supertone, 2026) — 폰 안에서 도는 고음질 다국어 음성 합성.
 * 공개 예제(MIT)의 Java 구현을 코틀린으로 옮긴 것. 모델 가중치는 OpenRAIL-M 라이선스.
 *
 * 구성: 길이 예측 → 텍스트 인코더 → 벡터 추정(흐름 매칭, N단계) → 보코더 → 44.1kHz 소리
 */
class Supertonic private constructor(
  private val env: OrtEnvironment,
  private val dp: OrtSession,
  private val textEnc: OrtSession,
  private val vectorEst: OrtSession,
  private val vocoder: OrtSession,
  private val indexer: LongArray,
  val sampleRate: Int,
  private val baseChunkSize: Int,
  private val chunkCompress: Int,
  private val latentDim: Int,
) : AutoCloseable {

  /** 목소리 하나 = 스타일 벡터 두 개 (ttl, dp) */
  class Voice(val ttl: FloatArray, val ttlShape: LongArray, val dp: FloatArray, val dpShape: LongArray) {
    /** 두 목소리를 섞는다. ratio=0 → 이 목소리, 1 → other */
    fun mix(other: Voice, ratio: Float): Voice {
      if (ratio <= 0f || !ttlShape.contentEquals(other.ttlShape)) return this
      if (ratio >= 1f) return other
      val r = ratio
      return Voice(
        FloatArray(ttl.size) { ttl[it] * (1 - r) + other.ttl[it] * r },
        ttlShape,
        FloatArray(dp.size) { dp[it] * (1 - r) + other.dp[it] * r },
        dpShape,
      )
    }
  }

  companion object {
    const val MODEL_FILES_BASE =
      "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/main/"

    /** (저장 경로, 받는 주소 상대경로, 대략 크기) */
    val FILES =
      listOf(
        "onnx/duration_predictor.onnx" to 3_700_147L,
        "onnx/text_encoder.onnx" to 36_416_150L,
        "onnx/vector_estimator.onnx" to 256_534_781L,
        "onnx/vocoder.onnx" to 101_424_195L,
        "onnx/tts.json" to 8_253L,
        "onnx/unicode_indexer.json" to 277_676L,
      ) + listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5").map {
        "voice_styles/$it.json" to 292_000L
      }

    val TOTAL_BYTES = FILES.sumOf { it.second }

    fun isInstalled(dir: File): Boolean = FILES.all { File(dir, it.first).let { f -> f.exists() && f.length() > 0 } }

    fun load(dir: File, threads: Int = 4): Supertonic {
      val env = OrtEnvironment.getEnvironment()
      val opts =
        OrtSession.SessionOptions().apply {
          setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
          setIntraOpNumThreads(threads)
        }
      fun session(name: String) = env.createSession(File(dir, "onnx/$name").absolutePath, opts)
      val cfg = JSONObject(File(dir, "onnx/tts.json").readText())
      val ae = cfg.getJSONObject("ae")
      val ttl = cfg.getJSONObject("ttl")
      val idxJson = JSONArray(File(dir, "onnx/unicode_indexer.json").readText())
      val indexer = LongArray(idxJson.length()) { idxJson.getLong(it) }
      return Supertonic(
        env,
        session("duration_predictor.onnx"),
        session("text_encoder.onnx"),
        session("vector_estimator.onnx"),
        session("vocoder.onnx"),
        indexer,
        ae.getInt("sample_rate"),
        ae.getInt("base_chunk_size"),
        ttl.getInt("chunk_compress_factor"),
        ttl.getInt("latent_dim"),
      )
    }

    fun loadVoice(file: File): Voice {
      val root = JSONObject(file.readText())
      fun read(key: String): Pair<FloatArray, LongArray> {
        val o = root.getJSONObject(key)
        val dims = o.getJSONArray("dims")
        val shape = LongArray(3) { dims.getLong(it) }
        val out = FloatArray((shape[1] * shape[2]).toInt())
        var i = 0
        val data = o.getJSONArray("data")
        for (b in 0 until data.length()) {
          val rows = data.getJSONArray(b)
          for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            for (c in 0 until row.length()) {
              if (i < out.size) out[i++] = row.getDouble(c).toFloat()
            }
          }
        }
        return out to longArrayOf(1, shape[1], shape[2])
      }
      val (ttl, ttlShape) = read("style_ttl")
      val (dp, dpShape) = read("style_dp")
      return Voice(ttl, ttlShape, dp, dpShape)
    }
  }

  private val rng = Random()

  /**
   * 문장을 소리로 바꾼다. 결과는 -1..1 float PCM (모노, [sampleRate] Hz).
   * @param steps 품질 단계 (많을수록 좋지만 느림)
   * @param speed 1.0 보통, 클수록 빠름
   */
  @Synchronized
  fun synthesize(text: String, voice: Voice, lang: String = "ko", steps: Int = 5, speed: Float = 1.05f): FloatArray {
    val maxLen = if (lang == "ko" || lang == "ja") 120 else 300
    val chunks = chunkText(text, maxLen)
    val out = ArrayList<FloatArray>()
    for ((i, chunk) in chunks.withIndex()) {
      if (i > 0) out.add(FloatArray((0.25f * sampleRate).toInt()))
      out.add(inferOne(chunk, lang, voice, steps, speed))
    }
    val total = out.sumOf { it.size }
    val wav = FloatArray(total)
    var o = 0
    for (a in out) {
      System.arraycopy(a, 0, wav, o, a.size)
      o += a.size
    }
    return wav
  }

  private fun inferOne(text: String, lang: String, voice: Voice, steps: Int, speed: Float): FloatArray {
    val processed = preprocess(text, lang)
    val cps = processed.codePoints().toArray()
    if (cps.isEmpty()) return FloatArray(0)
    val ids = LongArray(cps.size) { i -> val cp = cps[i]; if (cp in indexer.indices) indexer[cp] else 0L }
    val n = ids.size.toLong()
    val textMask = FloatArray(ids.size) { 1f }

    val closeables = ArrayList<AutoCloseable>()
    try {
      val idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n)).also { closeables.add(it) }
      val maskT =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(textMask), longArrayOf(1, 1, n)).also { closeables.add(it) }
      val styleTtl =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.ttl), voice.ttlShape).also { closeables.add(it) }
      val styleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.dp), voice.dpShape).also { closeables.add(it) }

      // 1) 길이
      val dpRes = dp.run(mapOf("text_ids" to idsT, "style_dp" to styleDp, "text_mask" to maskT)).also { closeables.add(it) }
      val durValue = dpRes.get(0).value
      var duration =
        when (durValue) {
          is FloatArray -> durValue[0]
          is Array<*> -> (durValue[0] as FloatArray)[0]
          else -> 1f
        }
      duration /= speed.coerceIn(0.5f, 2.5f)

      // 2) 텍스트 인코딩
      val encRes = textEnc.run(mapOf("text_ids" to idsT, "style_ttl" to styleTtl, "text_mask" to maskT)).also { closeables.add(it) }
      val textEmb = encRes.get(0) as OnnxTensor

      // 3) 잡음에서 시작해 N단계로 다듬기
      val wavLen = (duration * sampleRate).toLong()
      val chunkSize = baseChunkSize * chunkCompress
      val latentLen = ((wavLen + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
      val dim = latentDim * chunkCompress
      var xt = FloatArray(dim * latentLen) { rng.nextGaussian().toFloat() }
      val latentMask = FloatArray(latentLen) { 1f }
      val totalStepT = OnnxTensor.createTensor(env, floatArrayOf(steps.toFloat())).also { closeables.add(it) }
      val latentMaskT =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLen.toLong())).also {
          closeables.add(it)
        }
      for (step in 0 until steps) {
        val stepT = OnnxTensor.createTensor(env, floatArrayOf(step.toFloat()))
        val xtT = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), longArrayOf(1, dim.toLong(), latentLen.toLong()))
        val res =
          vectorEst.run(
            mapOf(
              "noisy_latent" to xtT,
              "text_emb" to textEmb,
              "style_ttl" to styleTtl,
              "latent_mask" to latentMaskT,
              "text_mask" to maskT,
              "current_step" to stepT,
              "total_step" to totalStepT,
            )
          )
        xt = flatten3(res.get(0).value)
        res.close()
        stepT.close()
        xtT.close()
      }

      // 4) 보코더
      val latT =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), longArrayOf(1, dim.toLong(), latentLen.toLong())).also {
          closeables.add(it)
        }
      val vRes = vocoder.run(mapOf("latent" to latT)).also { closeables.add(it) }
      val wavAll =
        when (val v = vRes.get(0).value) {
          is Array<*> -> v[0] as FloatArray
          is FloatArray -> v
          else -> FloatArray(0)
        }
      val keep = minOf(wavAll.size, wavLen.toInt())
      return wavAll.copyOf(keep)
    } finally {
      closeables.asReversed().forEach {
        try {
          it.close()
        } catch (_: Throwable) {}
      }
    }
  }

  private fun flatten3(v: Any?): FloatArray {
    @Suppress("UNCHECKED_CAST") val a = v as Array<Array<FloatArray>>
    val d1 = a[0].size
    val d2 = a[0][0].size
    val out = FloatArray(d1 * d2)
    var i = 0
    for (r in a[0]) {
      System.arraycopy(r, 0, out, i, d2)
      i += d2
    }
    return out
  }

  private fun preprocess(input: String, lang: String): String {
    var t = Normalizer.normalize(input, Normalizer.Form.NFKD)
    val sb = StringBuilder()
    var i = 0
    while (i < t.length) {
      val cp = t.codePointAt(i)
      if (!isEmoji(cp)) sb.appendCodePoint(cp)
      i += Character.charCount(cp)
    }
    t = sb.toString()
    val repl =
      mapOf(
        "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"", "”" to "\"",
        "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ",
        "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ", "@" to " at ",
      )
    for ((k, v) in repl) t = t.replace(k, v)
    t = t.replace(Regex("[♥☆♡©\\\\]"), "")
    t = t.replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?")
    while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
    while (t.contains("''")) t = t.replace("''", "'")
    t = t.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return ""
    if (!Regex(".*[.!?;:,'\"“”‘’)\\]}…。」』】〉》›»]$").matches(t)) t += "."
    return "<$lang>$t</$lang>"
  }

  private fun isEmoji(cp: Int): Boolean =
    cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF || cp in 0x1F700..0x1F77F ||
      cp in 0x1F780..0x1F7FF || cp in 0x1F800..0x1F8FF || cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FA6F ||
      cp in 0x1FA70..0x1FAFF || cp in 0x2600..0x26FF || cp in 0x2700..0x27BF || cp in 0x1F1E6..0x1F1FF

  private fun chunkText(text: String, maxLen: Int): List<String> {
    val t = text.trim()
    if (t.length <= maxLen) return listOf(t)
    val sentences = t.split(Regex("(?<=[.!?。])\\s+"))
    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    for (s in sentences) {
      val pieces = if (s.length > maxLen) s.chunked(maxLen) else listOf(s)
      for (p in pieces) {
        if (cur.length + p.length + 1 > maxLen && cur.isNotEmpty()) {
          chunks.add(cur.toString().trim())
          cur.setLength(0)
        }
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(p)
      }
    }
    if (cur.isNotEmpty()) chunks.add(cur.toString().trim())
    return chunks
  }

  override fun close() {
    listOf(dp, textEnc, vectorEst, vocoder).forEach {
      try {
        it.close()
      } catch (_: Throwable) {}
    }
  }
}
