package com.kunwooroh.gemmaagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Todo(
  val id: Int,
  val task: String,
  val due: String,
  val done: Boolean,
  val createdAt: Long,
)

data class Note(
  val id: Int,
  val title: String,
  val content: String,
  val createdAt: Long,
)

/** 폰 내부 저장소(JSON 파일)에 할 일을 저장한다. 인터넷/서버를 사용하지 않는다. */
class TodoStore(context: Context) {
  private val file = File(context.filesDir, "todos.json")
  private val _items = MutableStateFlow(load())
  val items: StateFlow<List<Todo>> = _items

  @Synchronized
  fun add(task: String, due: String): Todo {
    val list = _items.value
    val todo =
      Todo(
        id = (list.maxOfOrNull { it.id } ?: 0) + 1,
        task = task.trim(),
        due = due.trim(),
        done = false,
        createdAt = System.currentTimeMillis(),
      )
    save(list + todo)
    return todo
  }

  @Synchronized
  fun setDone(id: Int, done: Boolean): Todo? {
    var updated: Todo? = null
    val list = _items.value.map {
      if (it.id == id) it.copy(done = done).also { t -> updated = t } else it
    }
    if (updated != null) save(list)
    return updated
  }

  @Synchronized
  fun delete(id: Int): Boolean {
    val list = _items.value
    val newList = list.filterNot { it.id == id }
    if (newList.size == list.size) return false
    save(newList)
    return true
  }

  @Synchronized
  fun clearDone(): Int {
    val list = _items.value
    val newList = list.filterNot { it.done }
    save(newList)
    return list.size - newList.size
  }

  private fun save(list: List<Todo>) {
    val arr = JSONArray()
    list.forEach {
      arr.put(
        JSONObject()
          .put("id", it.id)
          .put("task", it.task)
          .put("due", it.due)
          .put("done", it.done)
          .put("createdAt", it.createdAt)
      )
    }
    file.writeText(arr.toString())
    _items.value = list
  }

  private fun load(): List<Todo> =
    try {
      if (!file.exists()) emptyList()
      else {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
          val o = arr.getJSONObject(i)
          Todo(
            id = o.getInt("id"),
            task = o.getString("task"),
            due = o.optString("due", ""),
            done = o.optBoolean("done", false),
            createdAt = o.optLong("createdAt", 0L),
          )
        }
      }
    } catch (e: Exception) {
      emptyList()
    }
}

/** 폰 내부 저장소(JSON 파일)에 메모를 저장한다. */
class NoteStore(context: Context) {
  private val file = File(context.filesDir, "notes.json")
  private val _items = MutableStateFlow(load())
  val items: StateFlow<List<Note>> = _items

  @Synchronized
  fun add(title: String, content: String): Note {
    val list = _items.value
    val note =
      Note(
        id = (list.maxOfOrNull { it.id } ?: 0) + 1,
        title = title.trim(),
        content = content.trim(),
        createdAt = System.currentTimeMillis(),
      )
    save(list + note)
    return note
  }

  @Synchronized
  fun delete(id: Int): Boolean {
    val list = _items.value
    val newList = list.filterNot { it.id == id }
    if (newList.size == list.size) return false
    save(newList)
    return true
  }

  fun search(keyword: String): List<Note> {
    val k = keyword.trim()
    if (k.isEmpty()) return _items.value
    return _items.value.filter {
      it.title.contains(k, ignoreCase = true) || it.content.contains(k, ignoreCase = true)
    }
  }

  private fun save(list: List<Note>) {
    val arr = JSONArray()
    list.forEach {
      arr.put(
        JSONObject()
          .put("id", it.id)
          .put("title", it.title)
          .put("content", it.content)
          .put("createdAt", it.createdAt)
      )
    }
    file.writeText(arr.toString())
    _items.value = list
  }

  private fun load(): List<Note> =
    try {
      if (!file.exists()) emptyList()
      else {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
          val o = arr.getJSONObject(i)
          Note(
            id = o.getInt("id"),
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            createdAt = o.optLong("createdAt", 0L),
          )
        }
      }
    } catch (e: Exception) {
      emptyList()
    }
}

/** 간단한 앱 설정 저장 (백엔드, 생각 모드, 모델 경로). */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var useGpu: Boolean
    get() = prefs.getBoolean("use_gpu", true)
    set(v) = prefs.edit().putBoolean("use_gpu", v).apply()

  var thinking: Boolean
    get() = prefs.getBoolean("thinking", false)
    set(v) = prefs.edit().putBoolean("thinking", v).apply()

  var modelPath: String?
    get() = prefs.getString("model_path", null)
    set(v) = prefs.edit().putString("model_path", v).apply()
}
