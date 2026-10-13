package com.kunwooroh.gemmaagent.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.kunwooroh.gemmaagent.data.NoteStore
import com.kunwooroh.gemmaagent.data.SkillStore
import com.kunwooroh.gemmaagent.data.TodoStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemma 4가 스스로 호출하는 "에이전트 도구" 모음.
 * 모든 도구는 폰 안에서만 동작한다 (인터넷 사용 안 함).
 *
 * @param onToolUsed 도구가 실행될 때 채팅 화면에 표시할 짧은 설명을 전달한다.
 */
class AgentTools(
  private val context: Context,
  private val todos: TodoStore,
  private val notes: NoteStore,
  private val skills: SkillStore,
  private val onToolUsed: (String) -> Unit,
) : ToolSet {

  @Tool(
    description =
      "Load the full instructions of a skill by name. Call this FIRST whenever the user's request " +
        "matches one of the skills listed in the system instruction, then follow the returned instructions."
  )
  fun useSkill(@ToolParam(description = "Exact skill name from the skill list.") name: String): Map<String, Any> {
    val skill =
      skills.find(name)
        ?: return mapOf(
          "result" to "error",
          "message" to "skill '$name' not found",
          "available" to skills.enabledSkills().map { it.name },
        )
    onToolUsed("🧩 스킬 사용: ${skill.name}")
    return mapOf("skill" to skill.name, "instructions" to skill.body)
  }

  @Tool(
    description =
      "Get the current local date, time and weekday on the phone. Call this before answering " +
        "anything about today, tomorrow, dates, or times."
  )
  fun getCurrentDateTime(): Map<String, Any> {
    val now = Date()
    onToolUsed("🕒 현재 시간 확인")
    return mapOf(
      "date" to SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(now),
      "time" to SimpleDateFormat("HH:mm", Locale.KOREA).format(now),
      "weekday" to SimpleDateFormat("EEEE", Locale.KOREA).format(now),
    )
  }

  @Tool(description = "Add a new item to the user's to-do list (할 일 추가).")
  fun addTodo(
    @ToolParam(description = "The task to do, in Korean, short.") task: String,
    @ToolParam(description = "Due date or time as the user said it, e.g. '내일 오후 3시'. Default: empty string.")
    due: String = "",
  ): Map<String, Any> {
    val todo = todos.add(task, due)
    onToolUsed("✅ 할 일 추가: ${todo.task}")
    return mapOf("result" to "added", "id" to todo.id, "task" to todo.task, "due" to todo.due)
  }

  @Tool(description = "List the user's to-do items (할 일 목록 보기).")
  fun listTodos(
    @ToolParam(description = "Whether to include completed items. Default: false.")
    includeDone: Boolean = false
  ): Map<String, Any> {
    val list = todos.items.value.filter { includeDone || !it.done }
    onToolUsed("📋 할 일 목록 확인 (${list.size}개)")
    return mapOf(
      "count" to list.size,
      "items" to
        list.map {
          mapOf("id" to it.id, "task" to it.task, "due" to it.due, "done" to it.done)
        },
    )
  }

  @Tool(description = "Mark a to-do item as done (할 일 완료 처리). Call listTodos first to find the id.")
  fun completeTodo(@ToolParam(description = "The id of the to-do item.") id: Int): Map<String, Any> {
    val todo = todos.setDone(id, true) ?: return mapOf("result" to "error", "message" to "id $id not found")
    onToolUsed("☑️ 완료: ${todo.task}")
    return mapOf("result" to "done", "task" to todo.task)
  }

  @Tool(description = "Delete a to-do item (할 일 삭제). Call listTodos first to find the id.")
  fun deleteTodo(@ToolParam(description = "The id of the to-do item.") id: Int): Map<String, Any> {
    val ok = todos.delete(id)
    if (ok) onToolUsed("🗑️ 할 일 삭제 (#$id)")
    return mapOf("result" to if (ok) "deleted" else "not found")
  }

  @Tool(
    description =
      "Schedule a phone notification reminder after some minutes (리마인더/알림 예약). " +
        "Example: in 3 hours -> minutesFromNow=180."
  )
  fun setReminder(
    @ToolParam(description = "Reminder message shown in the notification, in Korean.") message: String,
    @ToolParam(description = "How many minutes from now to notify. Must be 1 or more.") minutesFromNow: Int,
  ): Map<String, Any> {
    if (minutesFromNow < 1) return mapOf("result" to "error", "message" to "minutesFromNow must be >= 1")
    Reminders.schedule(context, message, minutesFromNow)
    val h = minutesFromNow / 60
    val m = minutesFromNow % 60
    val whenText = buildString {
      if (h > 0) append("${h}시간 ")
      if (m > 0) append("${m}분 ")
      append("뒤")
    }
    onToolUsed("⏰ 리마인더 예약: $whenText · $message")
    return mapOf("result" to "scheduled", "inMinutes" to minutesFromNow, "message" to message)
  }

  @Tool(description = "Set an alarm in the phone's Clock app at a specific time (알람 설정).")
  fun setAlarm(
    @ToolParam(description = "Hour in 24-hour format, 0-23.") hour: Int,
    @ToolParam(description = "Minute, 0-59.") minute: Int,
    @ToolParam(description = "Alarm label. Default: empty string.") label: String = "",
  ): Map<String, Any> {
    if (hour !in 0..23 || minute !in 0..59) {
      return mapOf("result" to "error", "message" to "invalid time")
    }
    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return try {
      context.startActivity(intent)
      onToolUsed("⏰ 알람 설정: %02d:%02d".format(hour, minute))
      mapOf("result" to "alarm set", "time" to "%02d:%02d".format(hour, minute))
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to "No clock app available")
    }
  }

  @Tool(description = "Save a note on the phone (메모 저장).")
  fun saveNote(
    @ToolParam(description = "Short title of the note.") title: String,
    @ToolParam(description = "Full content of the note.") content: String,
  ): Map<String, Any> {
    val note = notes.add(title, content)
    onToolUsed("📝 메모 저장: ${note.title}")
    return mapOf("result" to "saved", "id" to note.id)
  }

  @Tool(description = "Search saved notes by keyword. Use an empty keyword to list all notes (메모 찾기).")
  fun searchNotes(
    @ToolParam(description = "Keyword to search. Default: empty string (all notes).") keyword: String = ""
  ): Map<String, Any> {
    val found = notes.search(keyword).takeLast(10)
    onToolUsed("🔎 메모 검색 (${found.size}개)")
    return mapOf(
      "count" to found.size,
      "notes" to found.map { mapOf("id" to it.id, "title" to it.title, "content" to it.content) },
    )
  }

  @Tool(
    description =
      "Calculate a math expression exactly. Supports + - * / % ^ and parentheses. " +
        "Always use this for arithmetic instead of computing yourself."
  )
  fun calculate(
    @ToolParam(description = "Math expression, e.g. '(12000*3)+4500'.") expression: String
  ): Map<String, Any> {
    return try {
      val v = Calculator.eval(expression)
      val text = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
        "%,d".format(v.toLong())
      } else {
        "%,.4f".format(v).trimEnd('0').trimEnd('.')
      }
      onToolUsed("🧮 계산: $expression = $text")
      mapOf("result" to text)
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to (e.message ?: "invalid expression"))
    }
  }

  @Tool(description = "Turn the phone's flashlight (torch) on or off (손전등/플래시).")
  fun setFlashlight(@ToolParam(description = "true to turn on, false to turn off.") on: Boolean): Map<String, Any> {
    return try {
      val cm = context.getSystemService(CameraManager::class.java)
      val id =
        cm.cameraIdList.firstOrNull {
          cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return mapOf("result" to "error", "message" to "no flashlight")
      cm.setTorchMode(id, on)
      onToolUsed(if (on) "🔦 손전등 켬" else "🔦 손전등 끔")
      mapOf("result" to if (on) "on" else "off")
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to (e.message ?: "flashlight error"))
    }
  }

  @Tool(description = "Get the phone's battery level and charging state (배터리).")
  fun getBatteryStatus(): Map<String, Any> {
    val bm = context.getSystemService(BatteryManager::class.java)
    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging =
      status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    onToolUsed("🔋 배터리 $pct%")
    return mapOf("percent" to pct, "charging" to charging)
  }

  @Tool(description = "Open an installed app by its name, e.g. '카카오톡', '유튜브', '카메라' (앱 열기).")
  fun openApp(@ToolParam(description = "App name as the user said it.") appName: String): Map<String, Any> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
    val query = appName.replace(" ", "").lowercase()
    val aliases =
      mapOf(
        "유튜브" to "youtube", "카톡" to "카카오톡", "크롬" to "chrome", "지도" to "maps",
        "사진" to "갤러리", "인스타" to "instagram", "네이버" to "naver",
      )
    val alt = aliases[query]?.lowercase()
    fun score(label: String): Int {
      val l = label.replace(" ", "").lowercase()
      return when {
        l == query || (alt != null && l == alt) -> 3
        l.startsWith(query) || (alt != null && l.startsWith(alt)) -> 2
        l.contains(query) || (alt != null && l.contains(alt)) -> 1
        else -> 0
      }
    }
    val best =
      apps
        .map { it to score(it.loadLabel(pm).toString()) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first ?: return mapOf("result" to "error", "message" to "app '$appName' not found")
    val label = best.loadLabel(pm).toString()
    return try {
      val intent =
        pm.getLaunchIntentForPackage(best.activityInfo.packageName)
          ?: return mapOf("result" to "error", "message" to "cannot open")
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      onToolUsed("📱 $label 열기")
      mapOf("result" to "opened", "app" to label)
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to (e.message ?: "cannot open"))
    }
  }

  @Tool(description = "Start a countdown timer in the Clock app (타이머). Example: 3 minutes -> seconds=180.")
  fun setTimer(
    @ToolParam(description = "Timer length in seconds.") seconds: Int,
    @ToolParam(description = "Timer label. Default: empty string.") label: String = "",
  ): Map<String, Any> {
    if (seconds < 1) return mapOf("result" to "error", "message" to "seconds must be >= 1")
    val intent =
      Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return try {
      context.startActivity(intent)
      val m = seconds / 60
      val sec = seconds % 60
      onToolUsed("⏱️ 타이머 " + (if (m > 0) "${m}분 " else "") + (if (sec > 0) "${sec}초" else ""))
      mapOf("result" to "timer started", "seconds" to seconds)
    } catch (e: Exception) {
      mapOf("result" to "error", "message" to "No clock app available")
    }
  }
}
