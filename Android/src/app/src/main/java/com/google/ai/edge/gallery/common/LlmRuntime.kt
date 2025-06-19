package com.google.ai.edge.gallery.common

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit
typealias CleanUpListener = () -> Unit

interface LlmRuntime {
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit)
    fun createSession(model: Model): Any // Return type will be specific to the runtime
    fun generateResponse(
        model: Model,
        input: String,
        images: List<Bitmap>,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener
    )
    fun resetSession(model: Model)
    fun cleanUp(model: Model)
    fun sizeInTokens(model: Model, text: String): Int
    fun cancelGenerateResponse(model: Model)
}
