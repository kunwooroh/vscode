package com.kunwooroh.gemmaagent.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 화면 없이 바로 사진을 찍어 갤러리(Pictures/GemmaAgent)에 저장한다.
 * 앱이 화면에 떠 있을 때만 카메라를 쓸 수 있다 (안드로이드 규칙).
 */
object PhotoTaker {

  data class Photo(val uri: Uri, val preview: Bitmap?)

  suspend fun take(context: Context, front: Boolean): Photo =
    withContext(Dispatchers.Main) {
      val provider = awaitProvider(context)
      val capture =
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
      val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
      provider.unbindAll()
      provider.bindToLifecycle(ProcessLifecycleOwner.get(), selector, capture)
      try {
        delay(700) // 노출·초점이 잡힐 시간
        val name = "Gemma_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values =
          ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GemmaAgent")
          }
        val options =
          ImageCapture.OutputFileOptions.Builder(
              context.contentResolver,
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              values,
            )
            .build()
        val uri =
          suspendCancellableCoroutine<Uri> { cont ->
            capture.takePicture(
              options,
              ContextCompat.getMainExecutor(context),
              object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                  val saved = output.savedUri
                  if (saved != null) cont.resume(saved)
                  else cont.resumeWithException(IllegalStateException("사진을 저장하지 못했습니다"))
                }

                override fun onError(exception: ImageCaptureException) {
                  cont.resumeWithException(exception)
                }
              },
            )
          }
        Photo(uri, withContext(Dispatchers.IO) { loadPreview(context, uri) })
      } finally {
        provider.unbind(capture)
      }
    }

  private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
      val future = ProcessCameraProvider.getInstance(context)
      future.addListener(
        {
          try {
            cont.resume(future.get())
          } catch (e: Throwable) {
            cont.resumeWithException(e)
          }
        },
        ContextCompat.getMainExecutor(context),
      )
    }

  /** 모델에게 보여줄 1024px 이하 미리보기 */
  fun loadPreview(context: Context, uri: Uri): Bitmap? =
    try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val maxSide = 1024
        if (w > maxSide || h > maxSide) {
          val scale = maxSide.toFloat() / maxOf(w, h)
          decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
      }
    } catch (e: Throwable) {
      null
    }
}
