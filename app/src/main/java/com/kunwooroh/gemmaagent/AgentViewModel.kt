package com.kunwooroh.gemmaagent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.tool
import com.kunwooroh.gemmaagent.data.NoteStore
import com.kunwooroh.gemmaagent.data.Settings
import com.kunwooroh.gemmaagent.data.SkillStore
import com.kunwooroh.gemmaagent.data.TodoStore
import com.kunwooroh.gemmaagent.llm.AudioRecorder
import com.kunwooroh.gemmaagent.llm.EngineState
import com.kunwooroh.gemmaagent.llm.LlmEngine
import com.kunwooroh.gemmaagent.llm.ModelManager
import com.kunwooroh.gemmaagent.llm.ModelOption
import com.kunwooroh.gemmaagent.llm.Prompts
import com.kunwooroh.gemmaagent.llm.Speaker
import com.kunwooroh.gemmaagent.tools.AgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

sealed interface ModelStatus {
  data object NoModel : ModelStatus
  data class Downloading(val name: String, val progress: Float) : ModelStatus
  data class Importing(val progress: Float) : ModelStatus
  data class Loading(val name: String) : ModelStatus
  data class Ready(val name: String, val engine: EngineState) : ModelStatus
  data class Failed(val message: String) : ModelStatus
}

enum class Sender { USER, AGENT, TOOL }

/** 음성 비서 화면의 상태 */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatItem(
  val id: Long,
  val sender: Sender,
  val text: String,
  val thought: String = "",
  val image: Bitmap? = null,
  val hasAudio: Boolean = false,
  val streaming: Boolean = false,
  val isError: Boolean = false,
)

private val END_WORDS = Regex("(그만|종료|됐어|고마워|수고했어|잘\\s?자)")

class AgentViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  val settings = Settings(ctx)
  val models = ModelManager(ctx)
  val todos = TodoStore(ctx)
  val notes = NoteStore(ctx)
  val skills = SkillStore(ctx)
  private val engine = LlmEngine(ctx)
  private val recorder = AudioRecorder()
  val speaker = Speaker(ctx)
  private val ids = AtomicLong(1)

  private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NoModel)
  val status: StateFlow<ModelStatus> = _status

  private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
  val messages: StateFlow<List<ChatItem>> = _messages

  private val _generating = MutableStateFlow(false)
  val generating: StateFlow<Boolean> = _generating

  private val _recording = MutableStateFlow(false)
  val recording: StateFlow<Boolean> = _recording

  private val _recordLevel = MutableStateFlow(0f)
  val recordLevel: StateFlow<Float> = _recordLevel

  private val _pendingImage = MutableStateFlow<Bitmap?>(null)
  val pendingImage: StateFlow<Bitmap?> = _pendingImage

  private val _installed = MutableStateFlow(models.installedModels())
  val installed: StateFlow<List<File>> = _installed

  private val _thinking = MutableStateFlow(settings.thinking)
  val thinking: StateFlow<Boolean> = _thinking

  private val _useGpu = MutableStateFlow(settings.useGpu)
  val useGpu: StateFlow<Boolean> = _useGpu

  private val _voiceReply = MutableStateFlow(settings.voiceReply)
  val voiceReply: StateFlow<Boolean> = _voiceReply

  private val _startInVoice = MutableStateFlow(settings.startInVoiceMode)
  val startInVoice: StateFlow<Boolean> = _startInVoice

  private val _userTitle = MutableStateFlow(settings.userTitle)
  val userTitle: StateFlow<String> = _userTitle

  // 음성 비서 화면
  private val _voiceOpen = MutableStateFlow(false)
  val voiceOpen: StateFlow<Boolean> = _voiceOpen

  private val _voiceState = MutableStateFlow(VoiceState.IDLE)
  val voiceState: StateFlow<VoiceState> = _voiceState

  private val _heard = MutableStateFlow("")
  val heard: StateFlow<String> = _heard

  private val _reply = MutableStateFlow("")
  val reply: StateFlow<String> = _reply

  /** 앱 실행 후 음성 화면을 자동으로 한 번 열었는지 */
  var autoOpenedVoice = false
  private var greeted = false
  private var endRequested = false
  @Volatile private var mutedTurn = false

  private var workJob: Job? = null
  private var currentAgentId: Long = -1

  private val agentTools = AgentTools(ctx, todos, notes, skills) { label -> onToolUsed(label) }

  init {
    speaker.onAllDone = { viewModelScope.launch(Dispatchers.Main) { onSpeechFinished() } }
    val saved = settings.modelPath
    if (saved != null && File(saved).exists()) loadModel(File(saved))
  }

  // ───────────── 모델 준비 ─────────────

  fun refreshInstalled() {
    _installed.value = models.installedModels()
  }

  fun download(option: ModelOption) {
    workJob?.cancel()
    workJob =
      viewModelScope.launch {
        _status.value = ModelStatus.Downloading(option.name, 0f)
        try {
          val file = models.download(option) { p -> _status.value = ModelStatus.Downloading(option.name, p) }
          refreshInstalled()
          loadModel(file)
        } catch (e: kotlinx.coroutines.CancellationException) {
          _status.value = ModelStatus.NoModel
          throw e
        } catch (e: Throwable) {
          _status.value = ModelStatus.Failed(e.message ?: "다운로드 실패")
        }
      }
  }

  fun importModel(uri: Uri) {
    workJob?.cancel()
    workJob =
      viewModelScope.launch {
        _status.value = ModelStatus.Importing(0f)
        try {
          val file = models.importFromUri(uri) { p -> _status.value = ModelStatus.Importing(p) }
          refreshInstalled()
          loadModel(file)
        } catch (e: kotlinx.coroutines.CancellationException) {
          _status.value = ModelStatus.NoModel
          throw e
        } catch (e: Throwable) {
          _status.value = ModelStatus.Failed(e.message ?: "불러오기 실패")
        }
      }
  }

  fun cancelWork() {
    workJob?.cancel()
    _status.value = ModelStatus.NoModel
  }

  fun deleteModel(file: File) {
    if (settings.modelPath == file.absolutePath) {
      engine.close()
      settings.modelPath = null
      _status.value = ModelStatus.NoModel
    }
    models.delete(file)
    refreshInstalled()
  }

  fun loadModel(file: File) {
    viewModelScope.launch {
      val name = file.nameWithoutExtension
      _status.value = ModelStatus.Loading(name)
      try {
        val state =
          engine.load(
            modelPath = file.absolutePath,
            preferGpu = settings.useGpu,
            tools = listOf(tool(agentTools)),
            systemPrompt = {
              Prompts.systemInstruction(
                settings.userTitle,
                skills.enabledSkills().map { it.name to it.description },
              )
            },
          )
        settings.modelPath = file.absolutePath
        if (state.backend == "CPU" && settings.useGpu) {
          settings.useGpu = false
          _useGpu.value = false
        }
        _status.value = ModelStatus.Ready(name, state)
      } catch (e: Throwable) {
        _status.value = ModelStatus.Failed(e.message ?: "모델 실행 실패")
      }
    }
  }

  fun backToSetup() {
    closeVoice()
    stopGeneration()
    engine.close()
    refreshInstalled()
    _status.value = ModelStatus.NoModel
  }

  // ───────────── 설정 ─────────────

  fun setUseGpu(v: Boolean) {
    settings.useGpu = v
    _useGpu.value = v
    settings.modelPath?.let { loadModel(File(it)) }
  }

  fun setThinking(v: Boolean) {
    settings.thinking = v
    _thinking.value = v
  }

  fun setVoiceReply(v: Boolean) {
    settings.voiceReply = v
    _voiceReply.value = v
    if (!v) speaker.stop()
  }

  fun setStartInVoice(v: Boolean) {
    settings.startInVoiceMode = v
    _startInVoice.value = v
  }

  fun setUserTitle(v: String) {
    settings.userTitle = v.trim()
    _userTitle.value = v.trim()
    newChat() // 새 호칭을 반영해 대화를 새로 시작
  }

  // ───────────── 스킬 ─────────────

  private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

  /** 스킬이 바뀌면 젬마가 새 목록을 알도록 대화를 새로 시작한다. */
  private fun afterSkillChange(msg: String) {
    toast(msg)
    if (engine.isReady) newChat()
  }

  fun addSkillFromUri(uri: Uri) {
    viewModelScope.launch {
      try {
        val name = skills.addFromUri(uri)
        afterSkillChange("스킬 추가: $name")
      } catch (e: Throwable) {
        toast(e.message ?: "스킬을 추가하지 못했습니다")
      }
    }
  }

  fun addSkillFromUrl(url: String) {
    viewModelScope.launch {
      try {
        val name = skills.addFromUrl(url)
        afterSkillChange("스킬 추가: $name")
      } catch (e: Throwable) {
        toast(e.message ?: "스킬을 추가하지 못했습니다")
      }
    }
  }

  fun setSkillEnabled(name: String, enabled: Boolean) {
    skills.setEnabled(name, enabled)
    afterSkillChange(if (enabled) "켜짐: $name" else "꺼짐: $name")
  }

  fun deleteSkill(name: String) {
    skills.delete(name)
    afterSkillChange("삭제: $name")
  }

  // ───────────── 대화 ─────────────

  fun newChat() {
    stopGeneration()
    speaker.stop()
    _messages.value = emptyList()
    _heard.value = ""
    _reply.value = ""
    viewModelScope.launch { engine.resetConversation() }
  }

  fun attachImage(uri: Uri) {
    viewModelScope.launch {
      _pendingImage.value =
        withContext(Dispatchers.IO) {
          try {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
              decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
              val w = info.size.width
              val h = info.size.height
              val maxSide = 1024
              if (w > maxSide || h > maxSide) {
                val scale = maxSide.toFloat() / maxOf(w, h)
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
              }
            }
          } catch (e: Throwable) {
            null
          }
        }
    }
  }

  fun clearImage() {
    _pendingImage.value = null
  }

  fun send(text: String) {
    val image = _pendingImage.value
    if (text.isBlank() && image == null) return
    _pendingImage.value = null
    sendInternal(text.trim(), image, null)
  }

  private fun shouldSpeak(): Boolean = speaker.isReady && (_voiceReply.value || _voiceOpen.value)

  private fun sendInternal(text: String, image: Bitmap?, audioWav: ByteArray?) {
    if (_generating.value || !engine.isReady) return
    val userId = ids.getAndIncrement()
    val userItem =
      ChatItem(
        id = userId,
        sender = Sender.USER,
        text = if (text.isEmpty() && audioWav != null) "🎤 …" else text,
        image = image,
        hasAudio = audioWav != null,
      )
    val agentId = ids.getAndIncrement()
    currentAgentId = agentId
    _messages.update {
      it + userItem + ChatItem(id = agentId, sender = Sender.AGENT, text = "", streaming = true)
    }
    _generating.value = true
    _reply.value = ""
    if (audioWav == null) _heard.value = text
    if (_voiceOpen.value) _voiceState.value = VoiceState.THINKING

    val speak = shouldSpeak()
    mutedTurn = false
    if (speak) speaker.begin()

    val pngBytes =
      image?.let { bmp ->
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
      }
    val prompt = if (text.isEmpty() && audioWav != null) "(음성 메시지)" else text

    // 음성 메시지면 첫 줄의 "» 들은 말"을 따로 떼어 낸다
    var awaitingHeard = audioWav != null
    val head = StringBuilder()

    fun emitAnswer(chunk: String) {
      if (chunk.isEmpty()) return
      _messages.update { list -> list.map { if (it.id == agentId) it.copy(text = it.text + chunk) else it } }
      _reply.update { it + chunk }
      if (speak && !mutedTurn) {
        speaker.feed(chunk)
        if (_voiceOpen.value && _voiceState.value == VoiceState.THINKING) _voiceState.value = VoiceState.SPEAKING
      }
    }

    fun resolveHead(force: Boolean) {
      val s = head.toString()
      val nl = s.indexOf('\n')
      if (nl < 0 && !force && s.length < 240) return
      awaitingHeard = false
      val firstLine = if (nl >= 0) s.substring(0, nl) else s
      val rest = if (nl >= 0) s.substring(nl + 1) else ""
      val trimmed = firstLine.trim()
      if (trimmed.startsWith(Prompts.HEARD_MARK)) {
        val heardText = trimmed.removePrefix(Prompts.HEARD_MARK).trim()
        onHeard(userId, heardText)
        emitAnswer(rest.trimStart())
      } else {
        emitAnswer(s)
      }
    }

    engine.send(
      text = prompt,
      imagePng = pngBytes,
      audioWav = audioWav,
      thinking = _thinking.value,
      onToken = { token, thought ->
        if (!thought.isNullOrEmpty()) {
          _messages.update { list ->
            list.map { if (it.id == agentId) it.copy(thought = it.thought + thought) else it }
          }
        }
        if (awaitingHeard) {
          head.append(token)
          resolveHead(force = false)
        } else {
          emitAnswer(token)
        }
      },
      onDone = {
        if (awaitingHeard) resolveHead(force = true)
        finish(agentId, null, speak)
      },
      onError = { msg -> finish(agentId, msg, speak) },
    )
  }

  private fun onHeard(userId: Long, heardText: String) {
    if (heardText.isEmpty()) return
    _heard.value = heardText
    _messages.update { list ->
      list.map { if (it.id == userId) it.copy(text = "🎤 $heardText") else it }
    }
    if (END_WORDS.containsMatchIn(heardText)) endRequested = true
  }

  private fun finish(agentId: Long, error: String?, spoke: Boolean) {
    _messages.update { list ->
      list.map {
        if (it.id == agentId) {
          when {
            error != null -> it.copy(streaming = false, isError = true, text = "⚠️ 오류: $error")
            it.text.isBlank() -> it.copy(streaming = false, text = "(중단됨)")
            else -> it.copy(streaming = false, text = it.text.trim())
          }
        } else it
      }
    }
    _generating.value = false
    if (error != null) _reply.value = "오류가 났습니다: $error"
    viewModelScope.launch(Dispatchers.Main) {
      if (spoke && mutedTurn) {
        speaker.stop()
        onSpeechFinished()
      } else if (spoke) {
        if (error != null) speaker.speak("죄송합니다. 오류가 났습니다.") else speaker.end()
      } else {
        onSpeechFinished()
      }
    }
  }

  private fun onToolUsed(label: String) {
    val toolItem = ChatItem(id = ids.getAndIncrement(), sender = Sender.TOOL, text = label)
    val agentId = currentAgentId
    _messages.update { list ->
      val idx = list.indexOfFirst { it.id == agentId }
      if (idx >= 0) list.toMutableList().apply { add(idx, toolItem) } else list + toolItem
    }
  }

  fun stopGeneration() {
    if (_generating.value) engine.stop()
  }

  /** 채팅 말풍선의 대답을 다시 듣기 */
  fun replay(item: ChatItem) {
    if (speaker.isReady) speaker.speak(item.text)
  }

  fun stopSpeaking() = speaker.stop()

  // ───────────── 녹음 ─────────────

  fun hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  fun startRecording() {
    if (_recording.value || _generating.value || !hasMicPermission()) return
    speaker.stop()
    recorder.start(detectSilence = true) { heardSpeech ->
      viewModelScope.launch(Dispatchers.Main) {
        if (heardSpeech) {
          stopRecording(send = true)
        } else {
          stopRecording(send = false)
          if (_voiceOpen.value) _voiceState.value = VoiceState.IDLE
        }
      }
    }
    _recording.value = true
    if (_voiceOpen.value) _voiceState.value = VoiceState.LISTENING
    viewModelScope.launch {
      while (isActive && recorder.isRecording) {
        _recordLevel.value = recorder.level
        delay(60)
      }
      _recordLevel.value = 0f
    }
  }

  fun stopRecording(send: Boolean) {
    if (!_recording.value) return
    _recording.value = false
    if (!send) {
      recorder.cancel()
      if (_voiceOpen.value) _voiceState.value = VoiceState.IDLE
      return
    }
    val wav = recorder.stop()
    if (wav == null) {
      if (_voiceOpen.value) _voiceState.value = VoiceState.IDLE
      return
    }
    val image = _pendingImage.value
    _pendingImage.value = null
    sendInternal("", image, wav)
  }

  // ───────────── 음성 비서 화면 ─────────────

  fun openVoice() {
    if (_voiceOpen.value) return
    _voiceOpen.value = true
    endRequested = false
    if (!greeted && speaker.isReady) {
      greeted = true
      val pending = todos.items.value.count { !it.done }
      val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
      val text = Prompts.greeting(settings.userTitle, pending, hour)
      _reply.value = text
      _voiceState.value = VoiceState.SPEAKING
      speaker.speak(text)
    } else {
      startRecording()
    }
  }

  fun closeVoice() {
    if (!_voiceOpen.value) return
    _voiceOpen.value = false
    stopRecording(send = false)
    speaker.stop()
    _voiceState.value = VoiceState.IDLE
  }

  /** 가운데 빛나는 원을 눌렀을 때 */
  fun tapOrb() {
    when (_voiceState.value) {
      VoiceState.IDLE -> {
        endRequested = false
        startRecording()
      }
      VoiceState.LISTENING -> stopRecording(send = true)
      VoiceState.THINKING -> stopGeneration()
      VoiceState.SPEAKING -> {
        // 말 끊기: 바로 다시 듣는다
        endRequested = false
        if (_generating.value) {
          // 아직 대답을 만드는 중이면 생성도 멈춘다. 끝나는 대로 다시 듣는다.
          mutedTurn = true
          stopGeneration()
          speaker.stop()
          _voiceState.value = VoiceState.THINKING
        } else {
          speaker.stop()
          startRecording()
        }
      }
    }
  }

  /** 대답 읽기가 끝났을 때: 음성 화면이면 다시 귀를 기울인다. */
  private fun onSpeechFinished() {
    if (!_voiceOpen.value) return
    if (_generating.value || _recording.value) return
    if (endRequested) {
      endRequested = false
      _voiceState.value = VoiceState.IDLE
      return
    }
    startRecording()
    if (!_recording.value) _voiceState.value = VoiceState.IDLE
  }

  override fun onCleared() {
    recorder.cancel()
    speaker.shutdown()
    engine.close()
    super.onCleared()
  }
}
