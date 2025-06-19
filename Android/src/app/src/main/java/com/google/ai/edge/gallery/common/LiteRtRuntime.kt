package com.google.ai.edge.gallery.common

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT
import com.google.ai.edge.gallery.data.Model
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

private const val TAG = "LiteRtRuntime"

class LiteRtRuntime : LlmRuntime {

    // Public nested class to be stored in model.instance
    data class LiteRtModelInstance(val engine: LlmInference, var session: LlmInferenceSession)

    private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

    override fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        Log.d(TAG, "Initializing LiteRT for model: ${model.name}")
        try {
            val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
            val accelerator = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)

            val preferredBackend = when (accelerator) {
                Accelerator.CPU.label -> LlmInference.Backend.CPU
                Accelerator.GPU.label -> LlmInference.Backend.GPU
                else -> LlmInference.Backend.GPU
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.getPath(context))
                .setMaxTokens(maxTokens)
                .setPreferredBackend(preferredBackend)
                .setMaxNumImages(if (model.llmSupportImage) MAX_IMAGE_COUNT else 0)
                .build()

            val llmInference = LlmInference.createFromOptions(context, options)
            val session = createNewSession(model, llmInference)
            model.instance = LiteRtModelInstance(engine = llmInference, session = session)
            onDone("")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LiteRT for model ${model.name}", e)
            onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error initializing LiteRT"))
        }
    }

    private fun createNewSession(model: Model, engine: LlmInference): LlmInferenceSession {
        val topK = model.getIntConfigValue(ConfigKey.TOPK, DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(ConfigKey.TOPP, DEFAULT_TOPP)
        val temperature = model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE)

        return LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temperature)
                .setGraphOptions(
                    GraphOptions.builder().setEnableVisionModality(model.llmSupportImage).build()
                )
                .build()
        )
    }

    override fun createSession(model: Model): Any {
        val instance = model.instance as? LiteRtModelInstance
            ?: throw IllegalStateException("LiteRT engine not initialized for model: ${model.name}. Call initialize first.")
        return instance.session
    }

    override fun generateResponse(
        model: Model,
        input: String,
        images: List<Bitmap>,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener
    ) {
        Log.d(TAG, "Generating response for model ${model.name}")
        val instance = model.instance as? LiteRtModelInstance
        if (instance == null) {
            Log.e(TAG, "LiteRT not initialized for model: ${model.name}. Cannot generate response.")
            resultListener("Error: Runtime not initialized for ${model.name}", true)
            cleanUpListener()
            return
        }

        cleanUpListeners[model.name] = cleanUpListener

        try {
            val session = instance.session
            session.addQueryChunk(input)
            for (image in images) {
                session.addImage(BitmapImageBuilder(image).build())
            }
            session.generateResponseAsync(resultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error during LiteRT inference for model ${model.name}", e)
            resultListener(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error during inference"), true)
            cleanUpListeners.remove(model.name)?.invoke()
        }
    }

    override fun resetSession(model: Model) {
        Log.d(TAG, "Resetting session for model '${model.name}'")
        val instance = model.instance as? LiteRtModelInstance
        if (instance == null) {
            Log.w(TAG, "Cannot reset session, LiteRT not initialized for model: ${model.name}")
            return
        }

        try {
            instance.session.close()
            instance.session = createNewSession(model, instance.engine)
            Log.d(TAG, "LiteRT session reset successfully for model '${model.name}'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset LiteRT session for model ${model.name}", e)
        }
    }

    override fun cleanUp(model: Model) {
        Log.d(TAG, "Cleaning up LiteRT for model: ${model.name}")
        val instance = model.instance as? LiteRtModelInstance
        if (instance == null) {
            Log.w(TAG, "LiteRT instance for model ${model.name} is null, nothing to clean up.")
            return
        }

        try {
            instance.session.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close LiteRT session for model ${model.name}: ${e.message}")
        }

        try {
            instance.engine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close LiteRT engine for model ${model.name}: ${e.message}")
        }

        cleanUpListeners.remove(model.name)?.invoke()

        model.instance = null
        Log.d(TAG, "LiteRT clean up done for model: ${model.name}")
    }

    override fun sizeInTokens(model: Model, text: String): Int {
        Log.d(TAG, "Getting sizeInTokens for model ${model.name}")
        val instance = model.instance as? LiteRtModelInstance
        if (instance == null) {
            Log.e(TAG, "LiteRT not initialized for model: ${model.name}. Cannot get sizeInTokens.")
            return 0
        }
        return try {
            instance.session.sizeInTokens(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sizeInTokens for model ${model.name}", e)
            0
        }
    }

    override fun cancelGenerateResponse(model: Model) {
        Log.d(TAG, "Cancelling generate response for model ${model.name}")
        val instance = model.instance as? LiteRtModelInstance
        if (instance == null) {
            Log.w(TAG, "Cannot cancel response, LiteRT not initialized for model: ${model.name}")
            return
        }
        try {
            instance.session.cancelGenerateResponseAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling response for model ${model.name}", e)
        }
    }

    private fun cleanUpMediapipeTaskErrorMessage(message: String): String {
        return message.replaceFirst("com.google.mediapipe.framework.MediaPipeException: ", "")
                      .replaceFirst("java.lang.RuntimeException: ", "")
    }
}
