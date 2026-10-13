package com.kunwooroh.gemmaagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kunwooroh.gemmaagent.AgentViewModel
import com.kunwooroh.gemmaagent.ChatItem
import com.kunwooroh.gemmaagent.ModelStatus
import com.kunwooroh.gemmaagent.Sender
import com.kunwooroh.gemmaagent.llm.Speaker

private val SUGGESTIONS =
  listOf(
    "내일 할 일 정리해줘: 은행, 보고서 마감, 장보기",
    "3시간 뒤에 약 먹으라고 알려줘",
    "내일 아침 7시에 알람 맞춰줘",
    "12500원짜리 4개랑 3800원짜리 7개 합계 계산해줘",
    "\"오늘 회의는 3시로 미뤄졌어요\" 영어로 번역해줘",
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AgentViewModel, ready: ModelStatus.Ready) {
  val context = LocalContext.current
  val messages by vm.messages.collectAsState()
  val generating by vm.generating.collectAsState()
  val recording by vm.recording.collectAsState()
  val level by vm.recordLevel.collectAsState()
  val pendingImage by vm.pendingImage.collectAsState()
  val thinking by vm.thinking.collectAsState()
  val useGpu by vm.useGpu.collectAsState()
  val voiceReply by vm.voiceReply.collectAsState()
  val startInVoice by vm.startInVoice.collectAsState()
  val userTitle by vm.userTitle.collectAsState()
  val ttsStatus by vm.speaker.status.collectAsState()
  var titleDialog by remember { mutableStateOf(false) }

  var input by remember { mutableStateOf("") }
  var menuOpen by remember { mutableStateOf(false) }
  var sheetOpen by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val multimodal = ready.engine.multimodal

  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) vm.startRecording()
    }
  val voicePermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) vm.openVoice()
    }
  val openVoice = {
    if (vm.hasMicPermission()) vm.openVoice() else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
  }
  // 앱을 열면 바로 음성 비서 화면으로
  LaunchedEffect(Unit) {
    if (vm.settings.startInVoiceMode && !vm.autoOpenedVoice && multimodal) {
      vm.autoOpenedVoice = true
      openVoice()
    }
  }

  val imagePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) vm.attachImage(uri)
    }

  LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("젬마 비서", fontWeight = FontWeight.Bold)
            Text(
              "${ready.name} · ${ready.engine.backend}" +
                (if (ready.engine.speculativeDecoding) " · 고속" else "") +
                (if (!multimodal) " · 텍스트 전용" else ""),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(onClick = { openVoice() }, enabled = multimodal) {
            Icon(Icons.Filled.GraphicEq, contentDescription = "음성 비서 모드")
          }
          IconButton(onClick = { vm.setVoiceReply(!voiceReply) }) {
            Icon(
              if (voiceReply) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
              contentDescription = if (voiceReply) "음성 대답 끄기" else "음성 대답 켜기",
            )
          }
          IconButton(onClick = { sheetOpen = true }) {
            Icon(Icons.Filled.Checklist, contentDescription = "할 일·메모")
          }
          IconButton(onClick = { vm.newChat() }) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "새 대화")
          }
          Box {
            IconButton(onClick = { menuOpen = true }) {
              Icon(Icons.Filled.MoreVert, contentDescription = "설정")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              DropdownMenuItem(
                text = { Text("GPU 가속") },
                trailingIcon = { Switch(checked = useGpu, onCheckedChange = null) },
                onClick = {
                  menuOpen = false
                  vm.setUseGpu(!useGpu)
                },
              )
              DropdownMenuItem(
                text = { Text("생각 모드 (느리지만 꼼꼼)") },
                trailingIcon = { Switch(checked = thinking, onCheckedChange = null) },
                onClick = { vm.setThinking(!thinking) },
              )
              DropdownMenuItem(
                text = { Text("앱 열면 바로 음성 모드") },
                trailingIcon = { Switch(checked = startInVoice, onCheckedChange = null) },
                onClick = { vm.setStartInVoice(!startInVoice) },
              )
              DropdownMenuItem(
                text = { Text(if (userTitle.isBlank()) "나를 부르는 호칭 정하기" else "호칭: $userTitle") },
                onClick = {
                  menuOpen = false
                  titleDialog = true
                },
              )
              if (ttsStatus == Speaker.Status.NO_KOREAN || ttsStatus == Speaker.Status.FAILED) {
                DropdownMenuItem(
                  text = { Text("한국어 음성 설치하기") },
                  onClick = {
                    menuOpen = false
                    try {
                      context.startActivity(
                        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      )
                    } catch (_: Exception) {}
                  },
                )
              }
              HorizontalDivider()
              DropdownMenuItem(
                text = { Text("모델 바꾸기") },
                onClick = {
                  menuOpen = false
                  vm.backToSetup()
                },
              )
            }
          }
        },
      )
    }
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (messages.isEmpty()) {
          EmptyState(onPick = { if (!generating) vm.send(it) })
        } else {
          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(messages, key = { it.id }) { item -> MessageRow(item, onReplay = { vm.replay(item) }) }
          }
        }
      }

      // 첨부한 사진 미리보기
      pendingImage?.let { bmp ->
        Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "첨부 사진",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
          )
          IconButton(onClick = { vm.clearImage() }) { Icon(Icons.Filled.Close, contentDescription = "사진 빼기") }
        }
      }

      // 입력창
      Surface(tonalElevation = 3.dp) {
        if (recording) {
          Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(onClick = { vm.stopRecording(send = false) }) {
              Icon(Icons.Filled.Close, contentDescription = "녹음 취소")
            }
            Column(Modifier.weight(1f)) {
              Text("듣고 있어요… (최대 30초)", fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.stopRecording(send = true) }) {
              Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기")
            }
          }
        } else {
          Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
              },
              enabled = multimodal && !generating,
            ) {
              Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "사진 첨부")
            }
            OutlinedTextField(
              value = input,
              onValueChange = { input = it },
              placeholder = { Text("무엇을 도와드릴까요?") },
              modifier = Modifier.weight(1f).heightIn(max = 160.dp),
              shape = RoundedCornerShape(24.dp),
              maxLines = 5,
            )
            Spacer(Modifier.width(6.dp))
            when {
              generating ->
                FilledIconButton(onClick = { vm.stopGeneration() }) {
                  Icon(Icons.Filled.Stop, contentDescription = "멈추기")
                }
              input.isBlank() && pendingImage == null ->
                FilledIconButton(
                  onClick = {
                    val granted =
                      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                  },
                  enabled = multimodal,
                ) {
                  Icon(Icons.Filled.Mic, contentDescription = "음성으로 말하기")
                }
              else ->
                FilledIconButton(
                  onClick = {
                    vm.send(input)
                    input = ""
                  }
                ) {
                  Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기")
                }
            }
          }
        }
      }
    }
  }

  if (sheetOpen) {
    ModalBottomSheet(onDismissRequest = { sheetOpen = false }) { TodoNotesSheet(vm) }
  }

  if (titleDialog) {
    var value by remember { mutableStateOf(userTitle) }
    AlertDialog(
      onDismissRequest = { titleDialog = false },
      title = { Text("어떻게 불러드릴까요?") },
      text = {
        Column {
          Text("예: 대표님, 사장님, 선생님 (비워두면 호칭 없이)", style = MaterialTheme.typography.bodySmall)
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            vm.setUserTitle(value)
            titleDialog = false
          }
        ) {
          Text("저장")
        }
      },
      dismissButton = { TextButton(onClick = { titleDialog = false }) { Text("취소") } },
    )
  }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("무엇이든 말하거나 적어주세요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
      "🎤 버튼을 누르면 음성으로 말할 수 있어요",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    SUGGESTIONS.forEach { s ->
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable { onPick(s) },
      ) {
        Text(s, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun MessageRow(item: ChatItem, onReplay: () -> Unit) {
  when (item.sender) {
    Sender.TOOL ->
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
          Text(
            item.text,
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      }
    Sender.USER ->
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
          shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.widthIn(max = 300.dp),
        ) {
          Column(Modifier.padding(12.dp)) {
            item.image?.let {
              Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(160.dp).clip(RoundedCornerShape(10.dp)),
              )
              if (item.text.isNotBlank()) Spacer(Modifier.height(6.dp))
            }
            if (item.text.isNotBlank()) {
              Text(item.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
          }
        }
      }
    Sender.AGENT ->
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
          shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
          color =
            if (item.isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.widthIn(max = 320.dp).animateContentSize(),
        ) {
          Column(Modifier.padding(12.dp)) {
            if (item.thought.isNotBlank()) {
              var open by remember { mutableStateOf(false) }
              Text(
                if (open) "💭 생각 과정 접기" else "💭 생각 과정 보기",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { open = !open }.padding(bottom = 4.dp),
              )
              if (open) {
                Text(
                  item.thought.trim(),
                  style = MaterialTheme.typography.bodySmall,
                  fontStyle = FontStyle.Italic,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(bottom = 6.dp),
                )
              }
            }
            if (item.text.isEmpty() && item.streaming) {
              Text("…", style = MaterialTheme.typography.titleMedium)
            } else {
              SelectionContainer { Text(item.text) }
              if (!item.streaming && !item.isError) {
                Icon(
                  Icons.AutoMirrored.Filled.VolumeUp,
                  contentDescription = "다시 듣기",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(top = 6.dp).size(18.dp).clickable { onReplay() },
                )
              }
            }
          }
        }
      }
  }
}

@Composable
private fun TodoNotesSheet(vm: AgentViewModel) {
  val todos by vm.todos.items.collectAsState()
  val notes by vm.notes.items.collectAsState()
  LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(16.dp)) {
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("할 일", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (todos.any { it.done }) TextButton(onClick = { vm.todos.clearDone() }) { Text("완료 항목 지우기") }
      }
      if (todos.isEmpty()) {
        Text("아직 없어요. \"내일 은행 가기 추가해줘\"처럼 말해보세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    items(todos, key = { "t${it.id}" }) { t ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { vm.todos.setDone(t.id, it) })
        Column(Modifier.weight(1f)) {
          Text(t.task, textDecoration = if (t.done) TextDecoration.LineThrough else null)
          if (t.due.isNotBlank()) {
            Text(t.due, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
          }
        }
        IconButton(onClick = { vm.todos.delete(t.id) }) { Icon(Icons.Filled.Delete, contentDescription = "삭제") }
      }
    }
    item {
      Spacer(Modifier.height(16.dp))
      HorizontalDivider()
      Spacer(Modifier.height(12.dp))
      Text("메모", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      if (notes.isEmpty()) {
        Text("아직 없어요. \"주차 위치 B2 34번 메모해줘\"처럼 말해보세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    items(notes.reversed(), key = { "n${it.id}" }) { n ->
      Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(n.title, fontWeight = FontWeight.SemiBold)
          Text(n.content, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { vm.notes.delete(n.id) }) { Icon(Icons.Filled.Delete, contentDescription = "삭제") }
      }
    }
    item { Spacer(Modifier.height(24.dp)) }
  }
}

