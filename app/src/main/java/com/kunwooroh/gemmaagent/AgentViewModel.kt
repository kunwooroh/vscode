package com.kunwooroh.gemmaagent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.tool
import com.kunwooroh.gemmaagent.data.NoteStore
import com.kunwooroh.gemmaagent.data.Settings
import com.kunwooroh.gemmaagent.data.TodoStore
import com.kunwooroh.gemmaagent.llm.AudioRecorder
import com.kunwooroh.gemmaagent.llm.EngineState
import com.kunwooroh.gemmaagent.llm.LlmEngine
import com.kunwooroh.gemmaagent.llm.ModelManager
import com.kunwooroh.gemmaagent.llm.ModelOption
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

class AgentViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  val settings = Settings(ctx)
  val models = ModelManager(ctx)
  val todos = TodoStore(ctx)
  val notes = NoteStore(ctx)
  private val engine = LlmEngine(ctx)
  private val recorder = AudioRecorder()
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

  private var workJob: Job? = null
  private var currentAgentId: Long = -1

  private val agentTools =
    AgentTools(ctx, todos, notes) { label -> onToolUsed(label) }

  init {
    val saved = settings.modelPath
    if (saved != null && File(saved).exists()) {
      loadModel(File(saved))
    }
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
          val file = models.download(option) { p ->
            _status.value = ModelStatus.Downloading(option.name, p)
          }
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
          )
        settings.modelPath = file.absolutePath
        if (state.backend == "CPU" && settings.useGpu) {
          // GPU가 안 되는 폰이면 다음부터 바로 CPU로 시작
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
    stopGeneration()
    engine.close()
    refreshInstalled()
    _status.value = ModelStatus.NoModel
  }

  fun setUseGpu(v: Boolean) {
    settings.useGpu = v
    _useGpu.value = v
    val path = settings.modelPath ?: return
    loadModel(File(path))
  }

  fun setThinking(v: Boolean) {
    settings.thinking = v
    _thinking.value = v
  }

  // ───────────── 대화 ─────────────

  fun newChat() {
    stopGeneration()
    _messages.value = emptyList()
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

  private fun sendInternal(text: String, image: Bitmap?, audioWav: ByteArray?) {
    if (_generating.value || !engine.isReady) return
    val userItem =
      ChatItem(
        id = ids.getAndIncrement(),
        sender = Sender.USER,
        text = if (text.isEmpty() && audioWav != null) "🎤 음성 메시지" else text,
        image = image,
        hasAudio = audioWav != null,
      )
    val agentId = ids.getAndIncrement()
    currentAgentId = agentId
    _messages.update {
      it + userItem + ChatItem(id = agentId, sender = Sender.AGENT, text = "", streaming = true)
    }
    _generating.value = true

    val pngBytes = image?.let { bmp ->
      ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    val prompt =
      if (text.isEmpty() && audioWav != null) "음성 메시지를 듣고 요청을 처리해 주세요." else text

    engine.send(
      text = prompt,
      imagePng = pngBytes,
      audioWav = audioWav,
      thinking = _thinking.value,
      onToken = { token, thought ->
        _messages.update { list ->
          list.map {
            if (it.id == agentId) {
              it.copy(text = it.text + token, thought = it.thought + (thought ?: ""))
            } else it
          }
        }
      },
      onDone = { finish(agentId, null) },
      onError = { msg -> finish(agentId, msg) },
    )
  }

  private fun finish(agentId: Long, error: String?) {
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
  }

  private fun onToolUsed(label: String) {
    val toolItem = ChatItem(id = ids.getAndIncrement(), sender = Sender.TOOL, text = label)
    val agentId = currentAgentId
    _messages.update { list ->
      val idx = list.indexOfFirst { it.id == agentId }
      if (idx >= 0) {
        list.toMutableList().apply { add(idx, toolItem) }
      } else list + toolItem
    }
  }

  fun stopGeneration() {
    if (_generating.value) engine.stop()
  }

  // ───────────── 음성 ─────────────

  fun startRecording() {
    if (_recording.value || _generating.value) return
    recorder.start(onAutoStop = { viewModelScope.launch { stopRecording(send = true) } })
    _recording.value = true
    viewModelScope.launch {
      while (isActive && recorder.isRecording) {
        _recordLevel.value = recorder.level
        delay(80)
      }
      _recordLevel.value = 0f
    }
  }

  fun stopRecording(send: Boolean) {
    if (!_recording.value) return
    _recording.value = false
    if (!send) {
      recorder.cancel()
      return
    }
    val wav = recorder.stop() ?: return
    val image = _pendingImage.value
    _pendingImage.value = null
    sendInternal("", image, wav)
  }

  override fun onCleared() {
    recorder.cancel()
    engine.close()
    super.onCleared()
  }
}
