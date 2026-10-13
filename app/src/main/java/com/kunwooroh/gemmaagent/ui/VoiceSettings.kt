package com.kunwooroh.gemmaagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunwooroh.gemmaagent.AgentViewModel
import com.kunwooroh.gemmaagent.llm.Speaker
import com.kunwooroh.gemmaagent.llm.Supertonic
import com.kunwooroh.gemmaagent.llm.VoiceConfig

/** 듣기 엔진과 목소리(엔진·성별·섞기·속도·높낮이)를 고르는 화면 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSettingsSheet(vm: AgentViewModel) {
  val listen by vm.listenEngine.collectAsState()
  val cfg by vm.voiceConfig.collectAsState()
  val stStatus by vm.speaker.stStatus.collectAsState()
  val sysStatus by vm.speaker.status.collectAsState()
  val download by vm.stDownload.collectAsState()

  Column(
    Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("목소리 · 듣기 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

    // ───── 듣기 ─────
    SectionTitle("듣기 (내 말을 알아듣는 엔진)")
    Choice(
      selected = listen == "phone",
      title = "폰 한국어 음성인식 (추천)",
      desc = "말을 정확하게 글자로 바꿔서 젬마에게 전달해요. 가능하면 폰 안에서만 처리합니다.",
    ) { vm.setListenEngine("phone") }
    Choice(
      selected = listen == "gemma",
      title = "Gemma가 직접 듣기 (실험)",
      desc = "소리를 그대로 Gemma에게 줘요. 폰에 따라 잘 못 알아들을 수 있어요.",
    ) { vm.setListenEngine("gemma") }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ───── 목소리 엔진 ─────
    SectionTitle("목소리 엔진")
    Choice(
      selected = cfg.engine == VoiceConfig.ENGINE_SUPERTONIC,
      title = "Supertonic 3 (최신 고음질 · 무료)",
      desc =
        when {
          download != null -> "내려받는 중…"
          stStatus == Speaker.StStatus.NOT_INSTALLED ->
            "여성 5 · 남성 5 목소리, 섞어서 새 목소리 만들기. 처음 한 번 약 %.0fMB 내려받기 필요".format(
              Supertonic.TOTAL_BYTES / 1e6
            )
          stStatus == Speaker.StStatus.LOADING -> "준비하는 중… (잠시만요)"
          stStatus == Speaker.StStatus.FAILED -> "불러오지 못했어요. 다시 내려받아 보세요."
          else -> "여성 5 · 남성 5 목소리, 섞어서 새 목소리 만들기. 폰 안에서만 동작"
        },
    ) {
      if (stStatus == Speaker.StStatus.NOT_INSTALLED || stStatus == Speaker.StStatus.FAILED) vm.downloadVoiceEngine()
      vm.setVoiceConfig(cfg.copy(engine = VoiceConfig.ENGINE_SUPERTONIC))
    }
    download?.let { p ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
        LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("${(p * 100).toInt()}%")
        TextButton(onClick = { vm.cancelVoiceEngineDownload() }) { Text("취소") }
      }
    }
    if (stStatus == Speaker.StStatus.NOT_INSTALLED && download == null) {
      OutlinedButton(onClick = { vm.downloadVoiceEngine() }, modifier = Modifier.padding(start = 48.dp)) {
        Icon(Icons.Filled.Download, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("내려받기 (와이파이 추천)")
      }
    }
    Choice(
      selected = cfg.engine == VoiceConfig.ENGINE_SYSTEM,
      title = "폰 기본 음성",
      desc = "삼성·구글 등 폰에 깔린 음성 엔진과 목소리를 써요.",
    ) { vm.setVoiceConfig(cfg.copy(engine = VoiceConfig.ENGINE_SYSTEM)) }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    if (cfg.engine == VoiceConfig.ENGINE_SUPERTONIC) {
      // ───── Supertonic 목소리 ─────
      SectionTitle("목소리 고르기")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Speaker.ST_VOICES.forEach { v ->
          FilterChip(
            selected = cfg.stVoice == v,
            onClick = { vm.setVoiceConfig(cfg.copy(stVoice = v)) },
            label = { Text(Speaker.stVoiceLabel(v)) },
          )
        }
      }
      SectionTitle("다른 목소리와 섞기")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
          selected = cfg.stVoice2.isBlank() || cfg.stBlend <= 0f,
          onClick = { vm.setVoiceConfig(cfg.copy(stVoice2 = "", stBlend = 0f)) },
          label = { Text("안 섞음") },
        )
        Speaker.ST_VOICES.filter { it != cfg.stVoice }.forEach { v ->
          FilterChip(
            selected = cfg.stVoice2 == v && cfg.stBlend > 0f,
            onClick = {
              vm.setVoiceConfig(cfg.copy(stVoice2 = v, stBlend = if (cfg.stBlend <= 0f) 0.5f else cfg.stBlend))
            },
            label = { Text(Speaker.stVoiceLabel(v)) },
          )
        }
      }
      if (cfg.stVoice2.isNotBlank() && cfg.stBlend > 0f) {
        LabeledSlider(
          label = "섞는 비율: ${Speaker.stVoiceLabel(cfg.stVoice)} ${(100 - cfg.stBlend * 100).toInt()}% · " +
            "${Speaker.stVoiceLabel(cfg.stVoice2)} ${(cfg.stBlend * 100).toInt()}%",
          value = cfg.stBlend,
          range = 0.05f..0.95f,
        ) { vm.setVoiceConfig(cfg.copy(stBlend = it)) }
      }
      LabeledSlider("말 빠르기: ${"%.2f".format(cfg.stSpeed)}배", cfg.stSpeed, 0.7f..1.6f) {
        vm.setVoiceConfig(cfg.copy(stSpeed = it))
      }
      SectionTitle("음질")
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(3 to "빠름", 5 to "보통", 10 to "고음질").forEach { (steps, label) ->
          FilterChip(
            selected = cfg.stSteps == steps,
            onClick = { vm.setVoiceConfig(cfg.copy(stSteps = steps)) },
            label = { Text(label) },
          )
        }
      }
    } else {
      // ───── 폰 기본 음성 ─────
      val engines = remember(sysStatus) { vm.speaker.systemEngines() }
      val voices = remember(sysStatus, cfg.sysEngine) { vm.speaker.systemKoreanVoices() }
      if (engines.size > 1) {
        SectionTitle("음성 엔진")
        engines.forEach { (pkg, label) ->
          Choice(
            selected = cfg.sysEngine == pkg || (cfg.sysEngine.isBlank() && pkg == engines.first().first),
            title = label,
            desc = pkg,
          ) { vm.setVoiceConfig(cfg.copy(sysEngine = pkg, sysVoice = "")) }
        }
      }
      SectionTitle("목소리 (${voices.size}개)")
      if (sysStatus == Speaker.Status.NO_KOREAN) {
        Text("이 엔진에 한국어 음성이 없어요. 다른 엔진을 고르거나 한국어 음성을 설치하세요.")
      }
      voices.forEachIndexed { i, v ->
        Choice(
          selected = cfg.sysVoice == v.name,
          title = "목소리 ${i + 1}" + if (v.isNetworkConnectionRequired) " · 인터넷 필요" else " · 오프라인",
          desc = v.name,
        ) { vm.setVoiceConfig(cfg.copy(sysVoice = v.name)) }
      }
      LabeledSlider("목소리 높낮이: ${"%.2f".format(cfg.sysPitch)}", cfg.sysPitch, 0.5f..2.0f) {
        vm.setVoiceConfig(cfg.copy(sysPitch = it))
      }
      LabeledSlider("말 빠르기: ${"%.2f".format(cfg.sysRate)}배", cfg.sysRate, 0.5f..2.0f) {
        vm.setVoiceConfig(cfg.copy(sysRate = it))
      }
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.previewVoice() }, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.PlayArrow, contentDescription = null)
      Spacer(Modifier.width(6.dp))
      Text("이 목소리로 들어보기")
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Choice(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(selected = selected, onClick = onClick)
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.Medium)
      if (desc.isNotBlank()) {
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun LabeledSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onChange: (Float) -> Unit,
) {
  Column {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
  }
}
