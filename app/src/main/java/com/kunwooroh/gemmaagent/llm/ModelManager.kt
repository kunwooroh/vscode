package com.kunwooroh.gemmaagent.llm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class ModelOption(
  val name: String,
  val fileName: String,
  val url: String,
  val sizeBytes: Long,
  val minRamGb: Int,
  val description: String,
)

/** Gemma 4 모델 파일(.litertlm)을 내려받거나, 폰에 있는 파일을 앱으로 가져온다. */
class ModelManager(private val context: Context) {

  companion object {
    val OPTIONS =
      listOf(
        ModelOption(
          name = "Gemma 4 E2B",
          fileName = "gemma-4-E2B-it.litertlm",
          url =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
          sizeBytes = 2_588_147_712L,
          minRamGb = 8,
          description = "가볍고 빠름 · RAM 8GB 이상 폰 추천",
        ),
        ModelOption(
          name = "Gemma 4 E4B",
          fileName = "gemma-4-E4B-it.litertlm",
          url =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
          sizeBytes = 3_659_530_240L,
          minRamGb = 12,
          description = "더 똑똑함 · RAM 12GB 이상 폰 추천",
        ),
      )
  }

  /**
   * 모델 저장 폴더. USB로 직접 넣고 싶으면 PC에서 이 경로에 복사하면 된다:
   * 내장저장소/Android/data/com.kunwooroh.gemmaagent/files/models/
   */
  val modelsDir: File
    get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }

  fun installedModels(): List<File> =
    modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.sortedBy { it.name } ?: emptyList()

  fun deviceRamGb(): Double {
    val info = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return info.totalMem / (1024.0 * 1024.0 * 1024.0)
  }

  /** RAM에 맞는 추천 모델. 12GB 근처(실제 표시 11GB대)면 E4B, 아니면 E2B. */
  fun recommended(): ModelOption = if (deviceRamGb() >= 10.5) OPTIONS[1] else OPTIONS[0]

  private fun freeBytes(): Long = StatFs(modelsDir.absolutePath).availableBytes

  /** 인터넷에서 모델을 내려받는다. 중간에 끊기면 이어받기한다. */
  suspend fun download(option: ModelOption, onProgress: (Float) -> Unit): File =
    withContext(Dispatchers.IO) {
      val target = File(modelsDir, option.fileName)
      if (target.exists() && target.length() == option.sizeBytes) return@withContext target
      val part = File(modelsDir, option.fileName + ".part")
      val already = if (part.exists()) part.length() else 0L
      val need = option.sizeBytes - already + 200L * 1024 * 1024
      if (freeBytes() < need) {
        throw IllegalStateException(
          "저장공간이 부족합니다. 약 ${"%.1f".format(need / 1e9)}GB가 필요합니다."
        )
      }

      var url = URL(option.url)
      var conn: HttpURLConnection
      var redirects = 0
      while (true) {
        conn = (url.openConnection() as HttpURLConnection).apply {
          instanceFollowRedirects = false
          connectTimeout = 20_000
          readTimeout = 60_000
          if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        val code = conn.responseCode
        if (code in 300..399 && redirects < 5) {
          val loc = conn.getHeaderField("Location") ?: break
          url = URL(url, loc)
          conn.disconnect()
          redirects++
          continue
        }
        break
      }
      val code = conn.responseCode
      val append = code == HttpURLConnection.HTTP_PARTIAL
      if (code != HttpURLConnection.HTTP_OK && !append) {
        conn.disconnect()
        throw IllegalStateException("다운로드 실패 (HTTP $code)")
      }
      var done = if (append) already else 0L
      val total = option.sizeBytes
      conn.inputStream.use { input ->
        FileOutputStream(part, append).use { output ->
          val buf = ByteArray(1 shl 16)
          var lastReport = 0L
          while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (done - lastReport > 4L * 1024 * 1024) {
              lastReport = done
              onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
          }
        }
      }
      conn.disconnect()
      if (part.length() < total) {
        throw IllegalStateException("다운로드가 중간에 끊겼습니다. 다시 누르면 이어받습니다.")
      }
      if (target.exists()) target.delete()
      part.renameTo(target)
      onProgress(1f)
      target
    }

  /** Supertonic 3 목소리 엔진 파일들을 내려받는다 (약 400MB). */
  suspend fun downloadSupertonic(dir: File, onProgress: (Float) -> Unit) =
    withContext(Dispatchers.IO) {
      val total = Supertonic.TOTAL_BYTES.toFloat()
      var doneBefore = 0L
      for ((path, size) in Supertonic.FILES) {
        val target = File(dir, path)
        target.parentFile?.mkdirs()
        if (!(target.exists() && target.length() > 0)) {
          downloadFile(Supertonic.MODEL_FILES_BASE + path, target) { got ->
            onProgress(((doneBefore + got) / total).coerceIn(0f, 1f))
          }
        }
        doneBefore += size
        onProgress((doneBefore / total).coerceIn(0f, 1f))
      }
    }

  /** 파일 하나 내려받기 (리다이렉트 따라가기, 이어받기). */
  private suspend fun downloadFile(urlStr: String, target: File, onBytes: (Long) -> Unit) {
    val part = File(target.absolutePath + ".part")
    val already = if (part.exists()) part.length() else 0L
    var url = URL(urlStr)
    var conn: HttpURLConnection
    var redirects = 0
    while (true) {
      conn = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 60_000
        if (already > 0) setRequestProperty("Range", "bytes=$already-")
      }
      val code = conn.responseCode
      if (code in 300..399 && redirects < 6) {
        val loc = conn.getHeaderField("Location") ?: break
        url = URL(url, loc)
        conn.disconnect()
        redirects++
        continue
      }
      break
    }
    val code = conn.responseCode
    val append = code == HttpURLConnection.HTTP_PARTIAL
    if (code != HttpURLConnection.HTTP_OK && !append) {
      conn.disconnect()
      throw IllegalStateException("다운로드 실패 (HTTP $code)")
    }
    var done = if (append) already else 0L
    conn.inputStream.use { input ->
      FileOutputStream(part, append).use { output ->
        val buf = ByteArray(1 shl 16)
        var last = 0L
        while (true) {
          coroutineContext.ensureActive()
          val n = input.read(buf)
          if (n < 0) break
          output.write(buf, 0, n)
          done += n
          if (done - last > 2L * 1024 * 1024) {
            last = done
            onBytes(done)
          }
        }
      }
    }
    conn.disconnect()
    if (target.exists()) target.delete()
    part.renameTo(target)
  }

  /** 폰에 이미 받아둔 .litertlm 파일을 앱 폴더로 복사한다. */
  suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit): File =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      var name = "imported.litertlm"
      var size = -1L
      resolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val si = c.getColumnIndex(OpenableColumns.SIZE)
          if (ni >= 0) name = c.getString(ni) ?: name
          if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
      }
      if (!name.endsWith(".litertlm", ignoreCase = true)) {
        val hint =
          when {
            name.endsWith(".gguf", true) -> "GGUF는 PC용(Ollama·LM Studio) 형식이라 폰에서는 쓸 수 없습니다."
            name.endsWith(".safetensors", true) -> "safetensors는 변환 전 원본이라 바로 쓸 수 없습니다."
            name.endsWith(".task", true) -> ".task는 예전 MediaPipe 형식입니다."
            else -> "선택한 파일: $name"
          }
        throw IllegalArgumentException("폰용 Gemma 4 파일(.litertlm)이 아닙니다. $hint")
      }
      if (size > 0 && freeBytes() < size + 200L * 1024 * 1024) {
        throw IllegalStateException("저장공간이 부족합니다. 약 ${"%.1f".format(size / 1e9)}GB가 필요합니다.")
      }
      val target = File(modelsDir, name)
      val tmp = File(modelsDir, "$name.part")
      resolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tmp).use { output ->
          val buf = ByteArray(1 shl 16)
          var done = 0L
          var lastReport = 0L
          while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (size > 0 && done - lastReport > 8L * 1024 * 1024) {
              lastReport = done
              onProgress((done.toFloat() / size).coerceIn(0f, 1f))
            }
          }
        }
      } ?: throw IllegalStateException("파일을 열 수 없습니다.")
      if (target.exists()) target.delete()
      tmp.renameTo(target)
      onProgress(1f)
      target
    }

  fun delete(file: File) {
    file.delete()
  }
}
