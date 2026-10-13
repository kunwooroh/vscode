package com.kunwooroh.gemmaagent.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * 다른 앱 화면을 보고 조작하는 도구 (접근성 서비스가 켜져 있을 때만 젬마에게 주어진다).
 * 보내기·결제·삭제 같은 되돌리기 어려운 버튼은 사용자가 확인해야만 누른다.
 */
class ScreenTools(private val onToolUsed: (String) -> Unit) : ToolSet {

  private val sensitive =
    Regex("(보내기|전송|송금|이체|결제|구매|주문|삭제|탈퇴|해지|전화 걸기|통화|send|pay|buy|purchase|delete|remove|confirm)", RegexOption.IGNORE_CASE)

  private fun service() = ScreenControlService.instance

  private fun waitUi() = Thread.sleep(800)

  @Tool(
    description =
      "Read what is on the phone screen right now (화면 읽기). Returns numbered items like " +
        "'[3] 버튼: 확인'. Call this before tapping, and again after each action to see the result."
  )
  fun readScreen(): Map<String, Any> {
    val s = service() ?: return notEnabled()
    val (app, items) = s.readScreen()
    onToolUsed("👀 화면 읽기 (${items.size}개)")
    return mapOf(
      "app" to app,
      "items" to items.joinToString("\n") { "[${it.index}] ${it.role}: ${it.text}" },
    )
  }

  @Tool(
    description =
      "Tap an item on the screen by its number from read_screen, or by its visible text (화면 누르기). " +
        "For send/pay/delete/call buttons you must first ask the user and only then call again with confirmed=true."
  )
  fun tap(
    @ToolParam(description = "Item number like '3', or the visible text of the button.") target: String,
    @ToolParam(description = "true only after the user explicitly agreed. Default: false.") confirmed: Boolean = false,
  ): Map<String, Any> {
    val s = service() ?: return notEnabled()
    val item = findItem(s, target) ?: return mapOf("result" to "error", "message" to "'$target' not found. Call read_screen.")
    if (sensitive.containsMatchIn(item.text) && !confirmed) {
      return mapOf(
        "result" to "needs_confirmation",
        "message" to "'${item.text}' can't be undone. Ask the user to confirm, then call tap again with confirmed=true.",
      )
    }
    val ok = s.tapItem(item)
    onToolUsed("👆 누르기: ${item.text.ifBlank { "#${item.index}" }}")
    waitUi()
    return mapOf("result" to if (ok) "tapped" else "failed", "next" to "call read_screen to see the result")
  }

  @Tool(description = "Type text into an input box on the screen (글자 입력).")
  fun typeText(
    @ToolParam(description = "Text to type.") text: String,
    @ToolParam(description = "Input box number from read_screen. Default: empty string (focused box).")
    target: String = "",
  ): Map<String, Any> {
    val s = service() ?: return notEnabled()
    val item = if (target.isBlank()) null else findItem(s, target)
    val ok = s.typeText(text, item)
    onToolUsed("⌨️ 입력: ${text.take(20)}")
    waitUi()
    return mapOf("result" to if (ok) "typed" else "no input box found")
  }

  @Tool(description = "Scroll the screen (스크롤). direction: 'down' or 'up'.")
  fun scroll(@ToolParam(description = "'down' or 'up'.") direction: String): Map<String, Any> {
    val s = service() ?: return notEnabled()
    val down = !direction.trim().lowercase().startsWith("u") && direction.trim() != "위"
    val ok = s.scroll(down)
    onToolUsed(if (down) "⬇️ 아래로 스크롤" else "⬆️ 위로 스크롤")
    waitUi()
    return mapOf("result" to if (ok) "scrolled" else "failed")
  }

  @Tool(
    description =
      "Press a system button (시스템 버튼): back, home, recents, notifications, quick_settings, screenshot, lock."
  )
  fun pressButton(@ToolParam(description = "Button name.") button: String): Map<String, Any> {
    val s = service() ?: return notEnabled()
    val ok = s.pressGlobal(button)
    onToolUsed("🔘 $button")
    waitUi()
    return mapOf("result" to if (ok) "pressed" else "unknown button")
  }

  private fun findItem(s: ScreenControlService, target: String): ScreenControlService.ScreenItem? {
    val t = target.trim().removePrefix("[").removeSuffix("]").removePrefix("#")
    t.toIntOrNull()?.let { n -> s.lastItems.firstOrNull { it.index == n }?.let { return it } }
    // 글자로 찾기: 최신 화면을 다시 읽고 정확히 같은 것 → 포함하는 것 순서
    val items = s.readScreen().second
    return items.firstOrNull { it.text.equals(t, ignoreCase = true) && it.clickable }
      ?: items.firstOrNull { it.text.equals(t, ignoreCase = true) }
      ?: items.firstOrNull { it.text.contains(t, ignoreCase = true) && it.clickable }
      ?: items.firstOrNull { it.text.contains(t, ignoreCase = true) }
  }

  private fun notEnabled() =
    mapOf(
      "result" to "error",
      "message" to "Screen control is off. Tell the user to turn it on: menu ⋮ → 화면 조작 켜기.",
    )
}
