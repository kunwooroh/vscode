package com.kunwooroh.gemmaagent.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking

/**
 * 폰을 직접 조작하는 도구: 카메라, 전화·문자 준비, 기기 제어, 지도·검색 열기.
 * @param onPhoto 사진을 찍었을 때 호출. describe=true면 찍은 사진을 젬마가 바로 보고 설명한다.
 */
class PhoneTools(
  private val context: Context,
  private val onToolUsed: (String) -> Unit,
  private val onPhoto: (PhotoTaker.Photo, Boolean) -> Unit,
) : ToolSet {

  private fun granted(p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

  private fun start(intent: Intent): Boolean =
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: Exception) {
      false
    }

  @Tool(
    description =
      "Take a photo now with the phone camera and save it to the gallery (사진 찍기). " +
        "Set describe=true when the user wants you to look at it, e.g. '이거 뭐야', '앞에 뭐 있어'."
  )
  fun takePhoto(
    @ToolParam(description = "'back' (default) or 'front' (selfie).") camera: String = "back",
    @ToolParam(description = "true to look at the photo and describe it right after. Default: false.")
    describe: Boolean = false,
  ): Map<String, Any> {
    if (!granted(Manifest.permission.CAMERA)) {
      return mapOf("result" to "error", "message" to "camera permission not granted. Ask the user to allow it.")
    }
    if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      return mapOf("result" to "error", "message" to "the app must be on screen to use the camera")
    }
    return try {
      val photo = runBlocking { PhotoTaker.take(context, front = camera.lowercase().startsWith("f")) }
      onToolUsed(if (describe) "📸 사진 찍고 살펴보는 중" else "📸 사진 찍음 (갤러리에 저장)")
      onPhoto(photo, describe)
      mapOf(
        "result" to "photo saved to gallery",
        "next" to if (describe) "The photo will be shown to you in the next message. Say briefly that you took it." else "",
      )
    } catch (e: Throwable) {
      mapOf("result" to "error", "message" to (e.message ?: "camera error"))
    }
  }

  @Tool(description = "Open the camera app for the user to shoot themselves (카메라 앱 열기).")
  fun openCamera(
    @ToolParam(description = "true for video recording, false for photo. Default: false.") video: Boolean = false
  ): Map<String, Any> {
    val ok =
      start(Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    if (ok) onToolUsed(if (video) "🎥 동영상 카메라 열기" else "📷 카메라 열기")
    return mapOf("result" to if (ok) "opened" else "no camera app")
  }

  @Tool(
    description =
      "Prepare a phone call to a contact name or a number (전화 걸기). Opens the dialer with the number filled in; " +
        "the user presses call."
  )
  fun callContact(@ToolParam(description = "Contact name (e.g. '엄마') or phone number.") who: String): Map<String, Any> {
    val number = resolveNumber(who) ?: return mapOf("result" to "error", "message" to "contact '$who' not found")
    val ok = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number.second)}")))
    if (ok) onToolUsed("📞 전화 준비: ${number.first}")
    return mapOf("result" to if (ok) "dialer opened, user must press call" else "error", "name" to number.first)
  }

  @Tool(
    description =
      "Write a text message (SMS) to a contact name or number (문자 보내기). Opens the messaging app with the text " +
        "filled in; the user presses send."
  )
  fun sendMessage(
    @ToolParam(description = "Contact name or phone number.") to: String,
    @ToolParam(description = "Message text.") text: String,
  ): Map<String, Any> {
    val number = resolveNumber(to) ?: return mapOf("result" to "error", "message" to "contact '$to' not found")
    val intent =
      Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number.second)}")).putExtra("sms_body", text)
    val ok = start(intent)
    if (ok) onToolUsed("💬 문자 작성: ${number.first}")
    return mapOf("result" to if (ok) "message app opened with text, user must press send" else "error")
  }

  /** (표시 이름, 번호). 숫자면 그대로, 이름이면 연락처에서 찾는다. */
  private fun resolveNumber(who: String): Pair<String, String>? {
    if (Regex("^[+\\d\\s\\-()]{3,}$").matches(who.trim())) {
      return who.trim() to who.filter { it.isDigit() || it == '+' }
    }
    if (!granted(Manifest.permission.READ_CONTACTS)) return null
    val q = who.replace(Regex("(한테|에게|께|님)$"), "").trim()
    context.contentResolver
      .query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
          ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
          ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
        arrayOf("%$q%"),
        null,
      )
      ?.use { c ->
        var best: Pair<String, String>? = null
        while (c.moveToNext()) {
          val name = c.getString(0) ?: continue
          val num = c.getString(1) ?: continue
          if (name == q) return name to num
          if (best == null) best = name to num
        }
        return best
      }
    return null
  }

  @Tool(
    description =
      "Control the phone (기기 제어). action is one of: flashlight_on, flashlight_off, volume_up, volume_down, " +
        "volume_set (value 0-100), mute, unmute, media_play_pause, media_next, media_previous, battery, " +
        "open_settings (value: wifi, bluetooth, display, sound, battery, location, apps, notifications, data)."
  )
  fun controlDevice(
    @ToolParam(description = "The action name.") action: String,
    @ToolParam(description = "Extra value for volume_set or open_settings. Default: empty string.") value: String = "",
  ): Map<String, Any> {
    val audio = context.getSystemService(AudioManager::class.java)
    return try {
      when (action.trim().lowercase()) {
        "flashlight_on", "flashlight_off" -> {
          val on = action.endsWith("on")
          val cm = context.getSystemService(CameraManager::class.java)
          val id =
            cm.cameraIdList.firstOrNull {
              cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return mapOf("result" to "error", "message" to "no flashlight")
          cm.setTorchMode(id, on)
          onToolUsed(if (on) "🔦 손전등 켬" else "🔦 손전등 끔")
          mapOf("result" to if (on) "flashlight on" else "flashlight off")
        }
        "volume_up", "volume_down" -> {
          val dir = if (action.endsWith("up")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
          repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
          onToolUsed(if (dir == AudioManager.ADJUST_RAISE) "🔊 볼륨 올림" else "🔉 볼륨 내림")
          mapOf("result" to "ok", "volumePercent" to volumePercent(audio))
        }
        "volume_set" -> {
          val pct = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
          val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
          onToolUsed("🔊 볼륨 $pct%")
          mapOf("result" to "ok", "volumePercent" to pct)
        }
        "mute", "unmute" -> {
          val mute = action == "mute"
          audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI,
          )
          onToolUsed(if (mute) "🔇 음소거" else "🔊 음소거 해제")
          mapOf("result" to action)
        }
        "media_play_pause", "media_next", "media_previous" -> {
          val key =
            when (action) {
              "media_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
              "media_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
              else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
          val t = SystemClock.uptimeMillis()
          audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0))
          audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0))
          onToolUsed(
            when (action) {
              "media_next" -> "⏭️ 다음 곡"
              "media_previous" -> "⏮️ 이전 곡"
              else -> "⏯️ 재생/일시정지"
            }
          )
          mapOf("result" to "ok")
        }
        "battery" -> {
          val bm = context.getSystemService(BatteryManager::class.java)
          val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
          val st =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
              ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
          val charging =
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
          onToolUsed("🔋 배터리 $pct%")
          mapOf("percent" to pct, "charging" to charging)
        }
        "open_settings" -> {
          val target =
            when (value.trim().lowercase()) {
              "wifi", "와이파이" -> Settings.Panel.ACTION_WIFI
              "bluetooth", "블루투스" -> Settings.ACTION_BLUETOOTH_SETTINGS
              "display", "화면", "밝기" -> Settings.ACTION_DISPLAY_SETTINGS
              "sound", "소리" -> Settings.ACTION_SOUND_SETTINGS
              "battery", "배터리" -> Intent.ACTION_POWER_USAGE_SUMMARY
              "location", "위치" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
              "apps", "앱" -> Settings.ACTION_APPLICATION_SETTINGS
              "notifications", "알림" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
              "data", "데이터", "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
              else -> Settings.ACTION_SETTINGS
            }
          val intent = Intent(target)
          if (target == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          }
          val ok = start(intent) || start(Intent(Settings.ACTION_SETTINGS))
          onToolUsed("⚙️ 설정 열기${if (value.isNotBlank()) ": $value" else ""}")
          mapOf("result" to if (ok) "settings opened" else "error")
        }
        else -> mapOf("result" to "error", "message" to "unknown action '$action'")
      }
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to (e.message ?: "failed"))
    }
  }

  private fun volumePercent(audio: AudioManager): Int {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
  }

  @Tool(
    description =
      "Open a map, web search, or YouTube search (지도·길찾기·검색·유튜브). kind: 'map', 'web', or 'youtube'."
  )
  fun openSearch(
    @ToolParam(description = "'map', 'web', or 'youtube'.") kind: String,
    @ToolParam(description = "Place name or search words.") query: String,
  ): Map<String, Any> {
    val q = Uri.encode(query)
    val ok =
      when (kind.trim().lowercase()) {
        "map", "지도" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        "youtube", "유튜브" ->
          start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
        else ->
          start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.search.naver.com/search.naver?query=$q")))
      }
    if (ok) onToolUsed("🔎 ${if (kind.startsWith("map")) "지도" else "검색"}: $query")
    return mapOf("result" to if (ok) "opened" else "error")
  }
}
