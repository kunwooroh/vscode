package com.kunwooroh.gemmaagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunwooroh.gemmaagent.AgentViewModel
import com.kunwooroh.gemmaagent.ModelStatus
import com.kunwooroh.gemmaagent.llm.ModelManager

@Composable
fun AppRoot(vm: AgentViewModel) {
  val status by vm.status.collectAsState()
  when (val s = status) {
    is ModelStatus.Ready -> ChatScreen(vm, s)
    else -> SetupScreen(vm, s)
  }
}

@Composable
fun SetupScreen(vm: AgentViewModel, status: ModelStatus) {
  val installed by vm.installed.collectAsState()
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) vm.importModel(uri)
    }
  LaunchedEffect(Unit) { vm.refreshInstalled() }

  val ram = vm.models.deviceRamGb()
  val recommended = vm.models.recommended()
  val busy =
    status is ModelStatus.Downloading || status is ModelStatus.Importing || status is ModelStatus.Loading

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Filled.AutoAwesome,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("젬마 비서", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      }
      Text(
        "Gemma 4가 이 폰 안에서만 동작하는 개인 에이전트입니다. 대화 내용은 폰 밖으로 나가지 않아요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // 진행 상태
      when (status) {
        is ModelStatus.Downloading ->
          ProgressCard("${status.name} 내려받는 중", status.progress, onCancel = vm::cancelWork)
        is ModelStatus.Importing -> ProgressCard("모델 파일 가져오는 중", status.progress, onCancel = vm::cancelWork)
        is ModelStatus.Loading ->
          Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(Modifier.size(28.dp))
              Spacer(Modifier.width(14.dp))
              Column {
                Text("${status.name} 켜는 중…", fontWeight = FontWeight.SemiBold)
                Text(
                  "처음에는 10~30초 정도 걸릴 수 있어요.",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        is ModelStatus.Failed ->
          Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          ) {
            Text(
              "⚠️ ${status.message}",
              Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        else -> {}
      }

      // 이미 있는 모델
      if (installed.isNotEmpty()) {
        Text("폰에 있는 모델", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        installed.forEach { file ->
          Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, fontWeight = FontWeight.SemiBold)
                Text(
                  "%.1f GB".format(file.length() / 1e9),
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              IconButton(onClick = { vm.deleteModel(file) }, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "삭제")
              }
              Button(onClick = { vm.loadModel(file) }, enabled = !busy) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("시작")
              }
            }
          }
        }
      }

      // 내려받기
      Text("Gemma 4 내려받기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        "이 폰의 RAM: 약 %.0fGB · 와이파이에서 받으세요.".format(ram),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ModelManager.OPTIONS.forEach { option ->
        val isRec = option == recommended
        Card(
          Modifier.fillMaxWidth(),
          colors =
            if (isRec) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(),
        ) {
          Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(option.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
              if (isRec) Text("추천", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(
              "${option.description} · %.1fGB".format(option.sizeBytes / 1e9),
              style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.download(option) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Filled.Download, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("내려받고 시작")
            }
          }
        }
      }

      // 받아둔 파일 불러오기
      Text("받아둔 파일이 있다면", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      OutlinedButton(
        onClick = { picker.launch(arrayOf("*/*")) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("폰에서 .litertlm 파일 선택")
      }
      Text(
        "폰용 파일은 gemma-4-E2B-it.litertlm 처럼 .litertlm 로 끝납니다. " +
          "PC용(.gguf, Ollama·LM Studio)은 폰에서 쓸 수 없어요.\n" +
          "PC에서 USB로 넣을 경우: 내장저장소/Android/data/com.kunwooroh.gemmaagent/files/models/ 에 복사한 뒤 앱을 다시 열면 목록에 나옵니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun ProgressCard(title: String, progress: Float, onCancel: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${(progress * 100).toInt()}%")
      }
      Spacer(Modifier.height(8.dp))
      LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
      TextButton(onClick = onCancel) { Text("취소") }
    }
  }
}
