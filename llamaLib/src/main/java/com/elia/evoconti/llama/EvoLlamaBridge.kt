package com.elia.evoconti.llama

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Java-friendly facade around the official llama.cpp Android binding. */
class EvoLlamaBridge private constructor(context: Context) {
    fun interface TokenCallback {
        fun onToken(token: String): Boolean
    }

    private val engine = AiChat.getInferenceEngine(context.applicationContext)
    private var loadedPath: String? = null

    @Synchronized
    fun generate(
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
    ): String = generateStreaming(modelPath, systemPrompt, userPrompt, maxTokens) { true }

    @Synchronized
    fun generateStreaming(
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        callback: TokenCallback,
    ): String = runBlocking {
        ensureLoaded(modelPath)
        // Every consultation starts from a clean KV cache and sampler state.
        engine.setSystemPrompt(systemPrompt)
        val output = StringBuilder()
        engine.sendUserPrompt(userPrompt, maxTokens.coerceIn(32, 512)).collect { token ->
            if (!callback.onToken(token)) throw CancellationException("EvoCore request cancelled")
            output.append(token)
        }
        output.toString()
    }

    @Synchronized
    fun unload() {
        if (loadedPath == null) return
        engine.cleanUp()
        loadedPath = null
    }

    fun status(): String = engine.state.value.javaClass.simpleName

    private suspend fun ensureLoaded(modelPath: String) {
        if (loadedPath == modelPath && engine.state.value is InferenceEngine.State.ModelReady) return

        if (loadedPath != null) {
            engine.cleanUp()
            loadedPath = null
        }

        withTimeout(30_000) {
            engine.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
        }
        val state = engine.state.value
        if (state is InferenceEngine.State.Error) throw state.exception

        engine.loadModel(modelPath)
        loadedPath = modelPath
    }

    companion object {
        @Volatile private var instance: EvoLlamaBridge? = null

        @JvmStatic
        fun getInstance(context: Context): EvoLlamaBridge =
            instance ?: synchronized(this) {
                instance ?: EvoLlamaBridge(context).also { instance = it }
            }
    }
}
