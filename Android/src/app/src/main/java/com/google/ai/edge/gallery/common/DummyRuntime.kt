package com.google.ai.edge.gallery.common

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "DummyRuntime"

class DummyRuntime : LlmRuntime {

    private var simulationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    override fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        Log.d(TAG, "Initializing DummyRuntime for model: ${model.name}")
        model.instance = "DummyInstanceInitialized"
        onDone("")
    }

    override fun createSession(model: Model): Any {
        Log.d(TAG, "Creating session for DummyRuntime model: ${model.name}")
        if (model.instance == null) {
            Log.w(TAG, "DummyRuntime not initialized for ${model.name} before creating session.")
        }
        return "DummySessionFor-${model.name}"
    }

    override fun generateResponse(
        model: Model,
        input: String,
        images: List<Bitmap>,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener
    ) {
        Log.d(TAG, "Generating dummy response for model: ${model.name}, input: '$input', images: ${images.size}")

        simulationJob?.cancel()

        simulationJob = coroutineScope.launch {
            var currentResponse = ""
            val responsePrefix = "Echo from ${model.name} (Dummy): "
            try {
                if (!isActive) return@launch

                delay(200) // Simulate initial delay
                if (!isActive) return@launch

                if (input.isEmpty()) {
                    resultListener(responsePrefix, true)
                } else {
                    input.forEachIndexed { _, char ->
                        if (!isActive) throw kotlinx.coroutines.CancellationException("Cancelled during streaming")
                        delay(50)
                        currentResponse += char
                        resultListener(responsePrefix + currentResponse, false)
                    }
                    if (!isActive) throw kotlinx.coroutines.CancellationException("Cancelled after streaming")
                    delay(100)
                    resultListener(responsePrefix + currentResponse, true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Dummy response generation cancelled for model ${model.name}: ${e.message}")
                resultListener(responsePrefix + currentResponse + " (Cancelled)", true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in dummy response generation for model ${model.name}", e)
                resultListener("Error: ${e.message}", true)
            } finally {
                Log.d(TAG, "Executing finally block for ${model.name}, calling cleanUpListener.")
                cleanUpListener()
            }
        }
    }

    override fun resetSession(model: Model) {
        Log.d(TAG, "Resetting session for DummyRuntime model: ${model.name}")
        simulationJob?.cancel()
        Log.d(TAG, "Dummy session for ${model.name} considered reset.")
    }

    override fun cleanUp(model: Model) {
        Log.d(TAG, "Cleaning up DummyRuntime for model: ${model.name}")
        simulationJob?.cancel()
        model.instance = null
    }

    override fun sizeInTokens(model: Model, text: String): Int {
        Log.d(TAG, "Calculating sizeInTokens for DummyRuntime model: ${model.name}, text: '$text'")
        return text.split(" ").sumOf { it.length / 4 + 1 }
    }

    override fun cancelGenerateResponse(model: Model) {
        Log.d(TAG, "Request to cancel generateResponse for DummyRuntime model: ${model.name}")
        simulationJob?.cancel()
    }
}
