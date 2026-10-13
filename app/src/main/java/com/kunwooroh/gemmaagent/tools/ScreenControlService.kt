package com.kunwooroh.gemmaagent.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 접근성 서비스: 젬마가 다른 앱의 화면 글자를 읽고, 누르고, 입력하고, 스크롤한다.
 * 사용자가 설정 → 접근성 → 젬마 비서 → 사용 으로 직접 켜야 동작한다.
 */
class ScreenControlService : AccessibilityService() {

  companion object {
    @Volatile var instance: ScreenControlService? = null
      private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 시스템 설정에서 이 서비스가 켜져 있는지 (서비스가 아직 연결 전이어도 확인 가능) */
    fun isEnabled(context: Context): Boolean {
      val enabled =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
          ?: return false
      val me = ComponentName(context, ScreenControlService::class.java).flattenToString()
      return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
  }

  /** 마지막으로 읽은 화면의 항목들 (번호로 누를 수 있게) */
  data class ScreenItem(
    val index: Int,
    val text: String,
    val role: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
  )

  @Volatile var lastItems: List<ScreenItem> = emptyList()
    private set

  override fun onServiceConnected() {
    instance = this
    _connected.value = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onDestroy() {
    instance = null
    _connected.value = false
    super.onDestroy()
  }

  // ───────────── 화면 읽기 ─────────────

  fun readScreen(maxItems: Int = 60): Pair<String, List<ScreenItem>> {
    val root = rootInActiveWindow ?: return "" to emptyList()
    val app = root.packageName?.toString() ?: ""
    val items = mutableListOf<ScreenItem>()
    val seen = HashSet<String>()
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 40 || items.size >= maxItems) return
      if (!node.isVisibleToUser) return
      val text =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString())
          .map { it.trim().replace(Regex("\\s+"), " ") }
          .firstOrNull { it.isNotEmpty() }
      val clickable = node.isClickable || node.isLongClickable
      val editable = node.isEditable
      if (text != null || editable) {
        val r = Rect()
        node.getBoundsInScreen(r)
        val key = "${text}_${r.centerX()}_${r.centerY()}"
        if (r.width() > 0 && r.height() > 0 && seen.add(key)) {
          val role =
            when {
              editable -> "입력칸"
              node.isCheckable -> if (node.isChecked) "체크됨" else "체크박스"
              clickable || hasClickableParent(node) -> "버튼"
              else -> "글자"
            }
          items.add(
            ScreenItem(
              items.size + 1,
              (text ?: "").take(80),
              role,
              r,
              clickable || hasClickableParent(node),
              editable,
            )
          )
        }
      }
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(root, 0)
    lastItems = items
    return app to items
  }

  private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
    var p = node.parent
    var hops = 0
    while (p != null && hops < 4) {
      if (p.isClickable) return true
      p = p.parent
      hops++
    }
    return false
  }

  // ───────────── 동작 ─────────────

  private fun findNodeAt(bounds: Rect): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    var best: AccessibilityNodeInfo? = null
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 40) return
      val r = Rect()
      node.getBoundsInScreen(r)
      if (r == bounds) best = node
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(root, 0)
    return best
  }

  /** 항목을 누른다: 클릭 가능한 노드(또는 부모)를 누르고, 안 되면 그 위치를 손가락처럼 탭한다. */
  fun tapItem(item: ScreenItem): Boolean {
    var node = findNodeAt(item.bounds)
    var hops = 0
    while (node != null && !node.isClickable && hops < 5) {
      node = node.parent
      hops++
    }
    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    return tapAt(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
  }

  fun tapAt(x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y) }
    val gesture =
      GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
    return dispatchGesture(gesture, null, null)
  }

  fun typeText(text: String, target: ScreenItem?): Boolean {
    val node =
      target?.let { findNodeAt(it.bounds) }
        ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        ?: findFirstEditable(rootInActiveWindow)
        ?: return false
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.isEditable && node.isVisibleToUser) return node
    for (i in 0 until node.childCount) findFirstEditable(node.getChild(i))?.let { return it }
    return null
  }

  fun scroll(down: Boolean): Boolean {
    fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
      if (node == null) return null
      if (node.isScrollable && node.isVisibleToUser) return node
      for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
      return null
    }
    val target = findScrollable(rootInActiveWindow)
    if (target != null) {
      val ok =
        target.performAction(
          if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
      if (ok) return true
    }
    // 스크롤 영역을 못 찾으면 손가락으로 쓸어 넘긴다
    val dm = resources.displayMetrics
    val x = dm.widthPixels / 2f
    val y1 = dm.heightPixels * (if (down) 0.75f else 0.3f)
    val y2 = dm.heightPixels * (if (down) 0.3f else 0.75f)
    val path = Path().apply {
      moveTo(x, y1)
      lineTo(x, y2)
    }
    return dispatchGesture(
      GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(),
      null,
      null,
    )
  }

  fun pressGlobal(button: String): Boolean {
    val action =
      when (button.trim().lowercase()) {
        "back", "뒤로" -> GLOBAL_ACTION_BACK
        "home", "홈" -> GLOBAL_ACTION_HOME
        "recents", "최근" -> GLOBAL_ACTION_RECENTS
        "notifications", "알림" -> GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings", "빠른설정" -> GLOBAL_ACTION_QUICK_SETTINGS
        "screenshot", "캡처" -> GLOBAL_ACTION_TAKE_SCREENSHOT
        "lock", "잠금" -> GLOBAL_ACTION_LOCK_SCREEN
        else -> return false
      }
    return performGlobalAction(action)
  }
}
