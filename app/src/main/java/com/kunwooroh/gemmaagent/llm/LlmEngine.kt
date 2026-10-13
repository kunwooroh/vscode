package com.kunwooroh.gemmaagent.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

private const val TAG = "LlmEngine"
private const val THOUGHT_CHANNEL = "thought"

/** 엔진이 실제로 어떤 설정으로 켜졌는지. */
data class EngineState(
  val backend: String, // "GPU" or "CPU"
  val multimodal: Boolean, // 사진·음성 입력 가능 여부
  val speculativeDecoding: Boolean,
)

/**
 * LiteRT-LM으로 Gemma 4를 폰 안에서 직접 실행하는 래퍼.
 * - GPU 실패 시 CPU로, 멀티모달 실패 시 텍스트 전용으로 자동 전환
 * - 도구(에이전트 기능)는 LiteRT-LM이 자동으로 실행하고 결과를 모델에 돌려준다
 */
class LlmEngine(private val context: Context) {
  private var engine: Engine? = null
  private var conversation: Conversation? = null
  private var tools: List<ToolProvider> = emptyList()
  private var systemPrompt: () -> String = { Prompts.systemInstruction("") }

  var state: EngineState? = null
    private set

  val isReady: Boolean
    get() = engine != null && conversation != null

  @OptIn(ExperimentalApi::class)
  suspend fun load(
    modelPath: String,
    preferGpu: Boolean,
    tools: List<ToolProvider>,
    systemPrompt: () -> String,
  ): EngineState =
    withContext(Dispatchers.IO) {
      close()
      this@LlmEngine.tools = tools
      this@LlmEngine.systemPrompt = systemPrompt

      val mtp =
        try {
          ModelInfo.from(modelPath).use { info ->
            info is ModelInfo.Llm && info.hasSpeculativeDecodingSupport()
          }
        } catch (e: Throwable) {
          false
        }

      // 1단계: 원하는 백엔드 + 사진/음성 지원
      // 2단계: CPU + 사진/음성 지원
      // 3단계: CPU 텍스트 전용
      data class Attempt(val backend: String, val multimodal: Boolean)
      val attempts =
        buildList {
          if (preferGpu) add(Attempt("GPU", true))
          add(Attempt("CPU", true))
          add(Attempt("CPU", false))
        }

      var lastError: Throwable? = null
      for (attempt in attempts) {
        val mainBackend: Backend = if (attempt.backend == "GPU") Backend.GPU() else Backend.CPU()
        val config =
          EngineConfig(
            modelPath = modelPath,
            backend = mainBackend,
            visionBackend =
              if (attempt.multimodal) {
                if (attempt.backend == "GPU") Backend.GPU() else Backend.CPU()
              } else null,
            audioBackend = if (attempt.multimodal) Backend.CPU() else null, // 음성은 CPU 고정
            maxNumTokens = 8192,
            cacheDir = context.cacheDir.absolutePath,
          )
        val candidate = Engine(config)
        try {
          ExperimentalFlags.enableSpeculativeDecoding = mtp && attempt.backend == "GPU"
          try {
            candidate.initialize()
          } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
          }
          engine = candidate
          conversation = newConversation(candidate)
          val s = EngineState(attempt.backend, attempt.multimodal, mtp && attempt.backend == "GPU")
          state = s
          Log.i(TAG, "Engine ready: $s")
          return@withContext s
        } catch (e: Throwable) {
          Log.w(TAG, "Engine init failed for $attempt", e)
          lastError = e
          try {
            candidate.close()
          } catch (_: Throwable) {}
          engine = null
          conversation = null
        }
      }
      throw IllegalStateException(
        "모델을 실행하지 못했습니다: ${lastError?.message ?: "알 수 없는 오류"}",
        lastError,
      )
    }

  private fun newConversation(engine: Engine): Conversation =
    engine.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemPrompt()),
        tools = tools,
        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
      )
    )

  /** 젬마에게 주는 도구 목록을 바꾼다. 다음 resetConversation부터 적용. */
  fun setTools(tools: List<ToolProvider>) {
    this.tools = tools
  }

  /** 대화를 처음부터 다시 시작 (모델은 그대로 유지). */
  suspend fun resetConversation() =
    withContext(Dispatchers.IO) {
      val e = engine ?: return@withContext
      try {
        conversation?.close()
      } catch (_: Throwable) {}
      conversation = newConversation(e)
    }

  /**
   * 메시지를 보내고 답변을 스트리밍으로 받는다.
   * @param onToken (새 텍스트 조각, 새 생각 조각)
   */
  fun send(
    text: String,
    imagePng: ByteArray?,
    audioWav: ByteArray?,
    thinking: Boolean,
    onToken: (String, String?) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val conv = conversation
    if (conv == null) {
      onError("모델이 아직 준비되지 않았습니다.")
      return
    }
    val parts = mutableListOf<Content>()
    if (imagePng != null) parts.add(Content.ImageBytes(imagePng))
    if (audioWav != null) parts.add(Content.AudioBytes(audioWav))
    if (text.isNotBlank()) parts.add(Content.Text(text))
    if (parts.isEmpty()) {
      onDone()
      return
    }
    val tokenCb = onToken
    val doneCb = onDone
    val errorCb = onError
    try {
      conv.sendMessageAsync(
        Contents.of(parts),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            tokenCb(message.toString(), message.channels[THOUGHT_CHANNEL])
          }

          override fun onDone() {
            doneCb()
          }

          override fun onError(throwable: Throwable) {
            if (throwable is CancellationException) {
              doneCb()
            } else {
              Log.e(TAG, "inference error", throwable)
              errorCb(throwable.message ?: "알 수 없는 오류")
            }
          }
        },
        mapOf("enable_thinking" to thinking),
      )
    } catch (e: Throwable) {
      Log.e(TAG, "send failed", e)
      onError(e.message ?: "알 수 없는 오류")
    }
  }

  fun stop() {
    try {
      conversation?.cancelProcess()
    } catch (_: Throwable) {}
  }

  fun close() {
    try {
      conversation?.close()
    } catch (_: Throwable) {}
    try {
      engine?.close()
    } catch (_: Throwable) {}
    conversation = null
    engine = null
    state = null
  }
}
