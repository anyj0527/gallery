package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.CleanUpListener
import com.google.ai.edge.gallery.common.DummyRuntime
import com.google.ai.edge.gallery.common.LiteRtRuntime
import com.google.ai.edge.gallery.common.LlmRuntime
import com.google.ai.edge.gallery.common.ResultListener
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType

private const val TAG = "LlmChatModelHelper"

object LlmChatModelHelper {

    private fun getRuntime(model: Model): LlmRuntime {
        return when (model.runtimeType) {
            RuntimeType.LITE_RT -> LiteRtRuntime()
            RuntimeType.DUMMY -> DummyRuntime()
        }
    }

    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        Log.d(TAG, "Initializing model '${model.name}' with runtime ${model.runtimeType}")
        val runtime = getRuntime(model)
        runtime.initialize(context, model, onDone)
    }

    fun resetSession(model: Model) {
        Log.d(TAG, "Resetting session for model '${model.name}' using runtime ${model.runtimeType}")
        if (model.instance == null) {
            Log.w(TAG, "Model ${model.name} has no active instance to reset. Initialize first.")
            return
        }
        val runtime = getRuntime(model)
        runtime.resetSession(model)
    }

    fun cleanUp(model: Model) {
        Log.d(TAG, "Cleaning up model '${model.name}' using runtime ${model.runtimeType}")
        if (model.instance == null) {
            Log.w(TAG, "Model ${model.name} has no active instance to clean up.")
            return
        }
        val runtime = getRuntime(model)
        runtime.cleanUp(model)
    }

    fun generateResponse(
        model: Model,
        input: String,
        images: List<Bitmap> = listOf(),
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener
    ) {
        Log.d(TAG, "Generating response for model '${model.name}' with input '$input' using runtime ${model.runtimeType}")
        if (model.instance == null) {
            Log.e(TAG, "Model ${model.name} not initialized before calling generateResponse.")
            resultListener("Error: Model not initialized.", true)
            cleanUpListener()
            return
        }
        val runtime = getRuntime(model)
        runtime.generateResponse(model, input, images, resultListener, cleanUpListener)
    }

    fun cancelGenerateResponse(model: Model) {
        Log.d(TAG, "Cancelling response generation for model '${model.name}' using runtime ${model.runtimeType}")
        if (model.instance == null) {
            Log.w(TAG, "Model ${model.name} has no active instance to cancel response.")
            return
        }
        val runtime = getRuntime(model)
        runtime.cancelGenerateResponse(model)
    }

    fun sizeInTokens(model: Model, text: String): Int {
        Log.d(TAG, "Getting token size for model '${model.name}' text '$text' using runtime ${model.runtimeType}")
        if (model.instance == null) {
            Log.e(TAG, "Model ${model.name} not initialized before calling sizeInTokens.")
            return 0
        }
        val runtime = getRuntime(model)
        return runtime.sizeInTokens(model, text)
    }
}
