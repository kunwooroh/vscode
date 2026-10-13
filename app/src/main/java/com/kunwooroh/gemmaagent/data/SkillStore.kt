package com.kunwooroh.gemmaagent.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** SKILL.md 하나 = 스킬 하나. 젬마가 요청에 맞을 때 불러서 그 지침대로 일한다. */
data class Skill(
  val name: String,
  val description: String,
  val body: String,
  val enabled: Boolean,
  val builtIn: Boolean,
)

/**
 * 스킬 저장소 (폰 내부: files/skills/<이름>.md).
 * 형식은 AI Edge Gallery·Claude 스킬과 같은 SKILL.md:
 * ---
 * name: 스킬-이름
 * description: 언제 쓰는지 한 줄
 * ---
 * 지침 본문...
 */
class SkillStore(private val context: Context) {
  private val dir = File(context.filesDir, "skills").also { it.mkdirs() }
  private val prefs = context.getSharedPreferences("skills", Context.MODE_PRIVATE)
  private val _items = MutableStateFlow<List<Skill>>(emptyList())
  val items: StateFlow<List<Skill>> = _items

  init {
    installBuiltIns()
    reload()
  }

  /** 앱에 들어 있는 기본 스킬(assets/skills)을 처음 한 번 복사한다. 새 버전이면 갱신. */
  private fun installBuiltIns() {
    val version = 1
    if (prefs.getInt("builtin_version", 0) >= version) return
    val names = context.assets.list("skills") ?: emptyArray()
    val builtInNames = mutableSetOf<String>()
    for (asset in names) {
      if (!asset.endsWith(".md")) continue
      val text = context.assets.open("skills/$asset").bufferedReader().use { it.readText() }
      val parsed = parse(text) ?: continue
      File(dir, "${safeName(parsed.first)}.md").writeText(text)
      builtInNames.add(parsed.first)
    }
    prefs.edit()
      .putInt("builtin_version", version)
      .putStringSet("builtin_names", builtInNames)
      .apply()
  }

  fun reload() {
    val builtIns = prefs.getStringSet("builtin_names", emptySet()) ?: emptySet()
    val disabled = prefs.getStringSet("disabled", emptySet()) ?: emptySet()
    _items.value =
      (dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
        .mapNotNull { f ->
          val (name, desc, body) = parse(f.readText()) ?: return@mapNotNull null
          Skill(name, desc, body, enabled = name !in disabled, builtIn = name in builtIns)
        }
        .sortedWith(compareBy({ !it.builtIn }, { it.name }))
  }

  fun enabledSkills(): List<Skill> = _items.value.filter { it.enabled }

  fun find(name: String): Skill? {
    val q = name.trim().lowercase()
    return enabledSkills().firstOrNull { it.name.lowercase() == q }
      ?: enabledSkills().firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
  }

  fun setEnabled(name: String, enabled: Boolean) {
    val disabled = (prefs.getStringSet("disabled", emptySet()) ?: emptySet()).toMutableSet()
    if (enabled) disabled.remove(name) else disabled.add(name)
    prefs.edit().putStringSet("disabled", disabled).apply()
    reload()
  }

  fun delete(name: String) {
    File(dir, "${safeName(name)}.md").delete()
    reload()
  }

  /** 텍스트로 받은 SKILL.md를 저장. 성공하면 스킬 이름을 돌려준다. */
  fun addFromText(text: String): String {
    val parsed = parse(text) ?: throw IllegalArgumentException(
      "SKILL.md 형식이 아닙니다. 맨 위에 --- name: / description: --- 이 있어야 해요."
    )
    if (parsed.third.isBlank()) throw IllegalArgumentException("스킬 본문(지침)이 비어 있습니다.")
    File(dir, "${safeName(parsed.first)}.md").writeText(text)
    setEnabled(parsed.first, true)
    return parsed.first
  }

  suspend fun addFromUri(uri: Uri): String =
    withContext(Dispatchers.IO) {
      val text =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
          ?: throw IllegalStateException("파일을 열 수 없습니다.")
      addFromText(text)
    }

  /** GitHub 주소(blob)도 자동으로 원본(raw) 주소로 바꿔서 받는다. */
  suspend fun addFromUrl(rawUrl: String): String =
    withContext(Dispatchers.IO) {
      var url = rawUrl.trim()
      if (url.contains("github.com/") && url.contains("/blob/")) {
        url = url.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/")
      }
      if (url.contains("github.com/") && url.contains("/tree/")) {
        url = url.replace("github.com/", "raw.githubusercontent.com/").replace("/tree/", "/").trimEnd('/') + "/SKILL.md"
      }
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
      }
      val code = conn.responseCode
      if (code != 200) {
        conn.disconnect()
        throw IllegalStateException("받아오지 못했습니다 (HTTP $code)")
      }
      val text = conn.inputStream.bufferedReader().use { it.readText() }
      conn.disconnect()
      addFromText(text)
    }

  companion object {
    private fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(60)

    /** (name, description, body) */
    fun parse(text: String): Triple<String, String, String>? {
      val t = text.replace("\r\n", "\n").trimStart('﻿', ' ', '\n')
      if (!t.startsWith("---")) return null
      val end = t.indexOf("\n---", 3)
      if (end < 0) return null
      val front = t.substring(3, end)
      val body = t.substring(end + 4).trim()
      var name = ""
      var desc = ""
      for (line in front.lines()) {
        val idx = line.indexOf(':')
        if (idx < 0) continue
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim().trim('"', '\'')
        when (key) {
          "name" -> name = value
          "description" -> desc = value
        }
      }
      if (name.isBlank()) return null
      return Triple(name, desc, body)
    }
  }
}
