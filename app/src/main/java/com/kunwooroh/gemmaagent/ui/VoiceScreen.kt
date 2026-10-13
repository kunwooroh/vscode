package com.kunwooroh.gemmaagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunwooroh.gemmaagent.AgentViewModel
import com.kunwooroh.gemmaagent.VoiceState
import kotlin.math.min

private val BgTop = Color(0xFF05070D)
private val BgBottom = Color(0xFF0B1426)

private fun stateColor(s: VoiceState): Color =
  when (s) {
    VoiceState.IDLE -> Color(0xFF5B6B85)
    VoiceState.LISTENING -> Color(0xFF3FE0FF)
    VoiceState.THINKING -> Color(0xFFA98BFF)
    VoiceState.SPEAKING -> Color(0xFF7FB8FF)
  }

private fun stateLabel(s: VoiceState): String =
  when (s) {
    VoiceState.IDLE -> "눌러서 말하기"
    VoiceState.LISTENING -> "듣고 있어요"
    VoiceState.THINKING -> "생각하는 중"
    VoiceState.SPEAKING -> "말하는 중 · 눌러서 끊기"
  }

/** 전체 화면 음성 비서. 가운데 빛나는 원이 듣기·생각·말하기 상태를 보여준다. */
@Composable
fun VoiceScreen(vm: AgentViewModel) {
  val state by vm.voiceState.collectAsState()
  val level by vm.recordLevel.collectAsState()
  val heard by vm.heard.collectAsState()
  val reply by vm.reply.collectAsState()

  BackHandler { vm.closeVoice() }

  val color by animateColorAsState(stateColor(state), tween(500), label = "color")
  val smoothLevel by animateFloatAsState(level, tween(90), label = "level")

  val inf = rememberInfiniteTransition(label = "orb")
  val spin by
    inf.animateFloat(
      0f,
      360f,
      infiniteRepeatable(
        tween(if (state == VoiceState.THINKING) 1600 else 9000, easing = LinearEasing)
      ),
      label = "spin",
    )
  val breathe by
    inf.animateFloat(
      0f,
      1f,
      infiniteRepeatable(
        tween(if (state == VoiceState.SPEAKING) 420 else 1800, easing = FastOutSlowInEasing),
        RepeatMode.Reverse,
      ),
      label = "breathe",
    )

  val pulse =
    when (state) {
      VoiceState.LISTENING -> (smoothLevel * 3f).coerceIn(0f, 1f)
      VoiceState.SPEAKING -> 0.35f + breathe * 0.45f
      VoiceState.THINKING -> 0.25f + breathe * 0.15f
      VoiceState.IDLE -> breathe * 0.12f
    }

  val replyScroll = rememberScrollState()
  LaunchedEffect(reply.length) { replyScroll.animateScrollTo(replyScroll.maxValue) }

  Box(
    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom))).safeDrawingPadding()
  ) {
    Column(Modifier.fillMaxSize()) {
      // 상단
      Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.closeVoice() }) {
          Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Color(0xFF9FB3CF))
        }
        Text(
          "G E M M A",
          color = Color(0xFF9FB3CF),
          fontSize = 13.sp,
          letterSpacing = 4.sp,
          modifier = Modifier.weight(1f),
          textAlign = TextAlign.Center,
        )
        IconButton(onClick = { vm.closeVoice() }) {
          Icon(Icons.Filled.Keyboard, contentDescription = "글자로 대화", tint = Color(0xFF9FB3CF))
        }
      }

      // 들은 말
      Text(
        heard,
        color = Color(0xFF8A9BB5),
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).heightIn(min = 40.dp),
      )

      // 빛나는 원
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
          Modifier.size(280.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
              vm.tapOrb()
            }
        ) {
          val c = Offset(size.width / 2, size.height / 2)
          val r = min(size.width, size.height) / 2f

          // 바깥 번짐
          drawCircle(
            Brush.radialGradient(
              listOf(color.copy(alpha = 0.28f + pulse * 0.25f), Color.Transparent),
              center = c,
              radius = r * (0.95f + pulse * 0.08f),
            ),
            radius = r,
            center = c,
          )

          // 회전하는 호 3개
          val arcs = listOf(Triple(0.86f, 70f, 1f), Triple(0.74f, 110f, -1.4f), Triple(0.63f, 50f, 2.1f))
          arcs.forEachIndexed { i, (scale, sweep, speed) ->
            val rr = r * scale * (1f + pulse * 0.04f)
            rotate(spin * speed + i * 120f, c) {
              for (k in 0 until 2) {
                drawArc(
                  color = color.copy(alpha = 0.55f - i * 0.12f),
                  startAngle = k * 180f,
                  sweepAngle = sweep,
                  useCenter = false,
                  topLeft = Offset(c.x - rr, c.y - rr),
                  size = Size(rr * 2, rr * 2),
                  style = Stroke(width = (3 - i).dp.toPx(), cap = StrokeCap.Round),
                )
              }
            }
          }

          // 눈금 링
          val tickR = r * 0.52f
          for (t in 0 until 48) {
            val a = Math.toRadians((t * 7.5 + spin * 0.3))
            val inner = tickR - 4.dp.toPx() - pulse * 6.dp.toPx() * ((t % 3) / 2f)
            val start = Offset(c.x + (inner * Math.cos(a)).toFloat(), c.y + (inner * Math.sin(a)).toFloat())
            val end = Offset(c.x + (tickR * Math.cos(a)).toFloat(), c.y + (tickR * Math.sin(a)).toFloat())
            drawLine(color.copy(alpha = 0.35f), start, end, strokeWidth = 1.5.dp.toPx())
          }

          // 중심 코어
          val coreR = r * (0.30f + pulse * 0.09f)
          drawCircle(
            Brush.radialGradient(
              listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0f)),
              center = c,
              radius = coreR * 1.5f,
            ),
            radius = coreR * 1.5f,
            center = c,
          )
        }
      }

      // 상태
      Text(
        stateLabel(state),
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(14.dp))

      // 대답 (텍스트로도 보여줌)
      Column(
        Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 220.dp).verticalScroll(replyScroll).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
      ) {
        Text(
          reply.ifBlank { if (state == VoiceState.IDLE) "가운데를 누르고 말씀하세요." else "" },
          color = Color(0xFFE6EEFA),
          fontSize = 18.sp,
          lineHeight = 26.sp,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}
