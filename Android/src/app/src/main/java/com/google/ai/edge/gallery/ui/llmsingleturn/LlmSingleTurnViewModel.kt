package com.google.ai.edge.gallery.ui.llmsingleturn

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_LLM_PROMPT_LAB
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
// Removed: import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGLlmSingleTurnVM"

data class LlmSingleTurnUiState(
  val inProgress: Boolean = false,
  val preparing: Boolean = false,
  val responsesByModel: Map<String, Map<String, String>>,
  val benchmarkByModel: Map<String, Map<String, ChatMessageBenchmarkLlmResult>>,
  val selectedPromptTemplateType: PromptTemplateType = PromptTemplateType.entries[0],
)

private val STATS =
  listOf(
    Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
    Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
    Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
    Stat(id = "latency", label = "Latency", unit = "sec"),
  )

open class LlmSingleTurnViewModel(val task: Task = TASK_LLM_PROMPT_LAB) : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState(task = task))
  val uiState = _uiState.asStateFlow()

  fun generateResponse(model: Model, input: String) {
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      while (model.instance == null) {
        delay(100)
      }

      LlmChatModelHelper.resetSession(model = model)
      delay(500) // Give reset a moment

      val prefillTokens = LlmChatModelHelper.sizeInTokens(model, input)

      var firstRun = true
      var timeToFirstToken = 0f
      var firstTokenTs = 0L
      var decodeTokens = 0
      var prefillSpeed = 0f
      var decodeSpeed = 0f // Initialize to 0f
      val start = System.currentTimeMillis()
      var response = ""
      var lastBenchmarkUpdateTs = 0L
      LlmChatModelHelper.generateResponse(
        model = model,
        input = input,
        resultListener = { partialResult, done ->
          val curTs = System.currentTimeMillis()

          if (firstRun) {
            setPreparing(false)
            firstTokenTs = System.currentTimeMillis()
            timeToFirstToken = (firstTokenTs - start) / 1000f
            prefillSpeed = if (timeToFirstToken > 0f) prefillTokens / timeToFirstToken else 0f
            firstRun = false
          } else {
            decodeTokens++
          }

          response = processLlmResponse(response = "$response$partialResult")

          updateResponse(
            model = model,
            promptTemplateType = uiState.value.selectedPromptTemplateType,
            response = response,
          )

          val currentLatency = (curTs - start).toFloat() / 1000f
          val decodeDuration = if (firstTokenTs > 0) (curTs - firstTokenTs) / 1000f else 0f
          decodeSpeed = if (decodeDuration > 0f && decodeTokens > 0) decodeTokens / decodeDuration else 0f

          // Update benchmark (with throttling or if done)
          if (done || curTs - lastBenchmarkUpdateTs > 200) {
            val benchmark =
              ChatMessageBenchmarkLlmResult(
                orderedStats = STATS,
                statValues =
                  mutableMapOf(
                    "prefill_speed" to prefillSpeed,
                    "decode_speed" to decodeSpeed,
                    "time_to_first_token" to timeToFirstToken,
                    "latency" to currentLatency,
                  ),
                running = !done,
                latencyMs = -1f, // Or currentLatency * 1000 if that's what it means
              )
            updateBenchmark(
              model = model,
              promptTemplateType = uiState.value.selectedPromptTemplateType,
              benchmark = benchmark,
            )
            lastBenchmarkUpdateTs = curTs
          }

          if (done) {
            setInProgress(false)
          }
        },
        cleanUpListener = {
          setPreparing(false)
          setInProgress(false)
        },
      )
    }
  }

  fun selectPromptTemplate(model: Model, promptTemplateType: PromptTemplateType) {
    Log.d(TAG, "selecting prompt template: ${promptTemplateType.label}")
    updateResponse(model = model, promptTemplateType = promptTemplateType, response = "")
    this._uiState.update {
      this.uiState.value.copy(selectedPromptTemplateType = promptTemplateType)
    }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { _uiState.value.copy(inProgress = inProgress) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { _uiState.value.copy(preparing = preparing) }
  }

  fun updateResponse(model: Model, promptTemplateType: PromptTemplateType, response: String) {
    _uiState.update { currentState ->
      val currentResponses = currentState.responsesByModel
      val modelResponses = currentResponses[model.name]?.toMutableMap() ?: mutableMapOf()
      modelResponses[promptTemplateType.label] = response
      val newResponses = currentState.responsesByModel.toMutableMap()
      newResponses[model.name] = modelResponses
      currentState.copy(responsesByModel = newResponses)
    }
  }

  fun updateBenchmark(
    model: Model,
    promptTemplateType: PromptTemplateType,
    benchmark: ChatMessageBenchmarkLlmResult,
  ) {
    _uiState.update { currentState ->
      val currentBenchmark = currentState.benchmarkByModel
      val modelBenchmarks = currentBenchmark[model.name]?.toMutableMap() ?: mutableMapOf()
      modelBenchmarks[promptTemplateType.label] = benchmark
      val newBenchmarks = currentState.benchmarkByModel.toMutableMap()
      newBenchmarks[model.name] = modelBenchmarks
      currentState.copy(benchmarkByModel = newBenchmarks)
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      LlmChatModelHelper.cancelGenerateResponse(model)
    }
  }

  private fun createUiState(task: Task): LlmSingleTurnUiState {
    val responsesByModel: MutableMap<String, Map<String, String>> = mutableMapOf()
    val benchmarkByModel: MutableMap<String, Map<String, ChatMessageBenchmarkLlmResult>> =
      mutableMapOf()
    for (modelInTask in task.models) {
      responsesByModel[modelInTask.name] = mutableMapOf()
      benchmarkByModel[modelInTask.name] = mutableMapOf()
    }
    return LlmSingleTurnUiState(
      responsesByModel = responsesByModel,
      benchmarkByModel = benchmarkByModel,
    )
  }
}
