package com.conza.outpost.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Receives generated text pieces from native code. Return false to stop. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/** JNI surface of libnative outpost_llm (llama.cpp). Only present on 64-bit builds. */
object NativeLlm {
    @Volatile var available: Boolean = false
        private set
    @Volatile var loadError: String? = null
        private set
    private var initialized = false

    @Volatile var safeMode: Boolean = false
        private set

    /**
     * Loads the native engine once. A native crash can't be caught, so a guard file records
     * that loading is in progress: if the app died mid-load last time, retry in safe mode
     * (baseline CPU code only); if that also died, turn on-device AI off and say why.
     * Call from a background thread: loading takes a moment.
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        if (initialized) return available
        initialized = true
        val dir = context.filesDir
        val disabled = File(dir, "llm.disabled")
        val guard = File(dir, "llm.init.guard")
        val safeFlag = File(dir, "llm.safe")
        if (disabled.exists()) {
            loadError = "On-device AI crashed while starting on this device, so it's turned off. " +
                "You can retry from Settings, or use a server on your network."
            return false
        }
        if (guard.exists()) {
            if (safeFlag.exists()) {
                guard.delete()
                disabled.writeText("crashed in safe mode")
                loadError = "On-device AI crashed while starting on this device, so it's turned off. " +
                    "You can retry from Settings, or use a server on your network."
                return false
            }
            safeFlag.writeText("1") // last normal start crashed: use the baseline CPU backend from now on
        }
        safeMode = safeFlag.exists()
        guard.writeText(if (safeMode) "safe" else "normal")
        try {
            System.loadLibrary("outpost_llm")
            nativeInit(context.applicationInfo.nativeLibraryDir, safeMode)
            available = true
        } catch (t: Throwable) {
            loadError = when (t) {
                is UnsatisfiedLinkError -> "On-device AI isn't included in this build for your device's processor (it needs a 64-bit ARM or x86 CPU)."
                else -> t.message ?: t.javaClass.simpleName
            }
            Log.w("OutpostLLM", "native load failed", t)
        } finally {
            guard.delete()
        }
        return available
    }

    /** Clears the crash markers so the next start tries the engine again (Settings → Retry). */
    fun reset(context: Context) {
        listOf("llm.disabled", "llm.init.guard", "llm.safe").forEach { File(context.filesDir, it).delete() }
    }

    @JvmStatic external fun nativeInit(nativeLibDir: String, safeMode: Boolean)
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, embedding: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeContextSize(handle: Long): Int
    @JvmStatic external fun nativeCountTokens(handle: Long, text: String): Int
    @JvmStatic external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>,
        maxTokens: Int, temperature: Float, topP: Float, callback: TokenCallback,
    ): Int
    @JvmStatic external fun nativeEmbed(handle: Long, text: String): FloatArray?
}

class PromptTooLongException : Exception("Conversation is too long for the model's context window")

data class GenerationStats(val tokens: Int, val millis: Long) {
    val tokensPerSecond: Double get() = if (millis > 0) tokens * 1000.0 / millis else 0.0
}

/**
 * Owns the loaded on-device models. All native calls run on one dedicated thread because
 * a llama.cpp context is not thread-safe; [stop] is the only call that may come from elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalLlm(private val context: Context) {
    private val thread = Dispatchers.IO.limitedParallelism(1)

    private var chatHandle = 0L
    private var chatPath: String? = null
    private var chatCtx = 0
    private var embedHandle = 0L
    private var embedPath: String? = null

    /** May load native code the first time: call off the main thread (see [checkAvailable]). */
    val available: Boolean get() = NativeLlm.ensure(context)
    val unavailableReason: String? get() = if (available) null else NativeLlm.loadError

    suspend fun checkAvailable(): Boolean = withContext(Dispatchers.IO) { NativeLlm.ensure(context) }

    val loadedChatModel: String? get() = chatPath?.let { File(it).name }

    suspend fun ensureChatModel(file: File, nCtx: Int): Unit = withContext(thread) {
        check(available) { NativeLlm.loadError ?: "On-device AI unavailable" }
        if (chatHandle != 0L && chatPath == file.absolutePath && chatCtx == nCtx) return@withContext
        unloadChatLocked()
        val h = guarded("loading ${file.name}") { NativeLlm.nativeLoad(file.absolutePath, nCtx, 0, false) }
        if (h == 0L) error("Couldn't load ${file.name}. The file may be damaged, or the phone may not have enough free memory.")
        chatHandle = h
        chatPath = file.absolutePath
        chatCtx = nCtx
    }

    /** Streams an answer. Throws [PromptTooLongException] if the prompt doesn't fit. */
    suspend fun generate(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Boolean,
    ): GenerationStats = withContext(thread) {
        check(chatHandle != 0L) { "No chat model loaded" }
        var count = 0
        val start = System.currentTimeMillis()
        val rc = guarded("answering with ${loadedChatModel}") {
            NativeLlm.nativeGenerate(
                chatHandle,
                messages.map { it.first }.toTypedArray(),
                messages.map { it.second }.toTypedArray(),
                maxTokens, temperature, 0.95f,
            ) { piece -> count++; onToken(piece) }
        }
        when (rc) {
            -2 -> throw PromptTooLongException()
            -1, -3 -> error("The model failed while generating (code $rc).")
        }
        GenerationStats(count, System.currentTimeMillis() - start)
    }

    private val runGuard get() = File(context.filesDir, "llm.run.guard")

    /** Marks a native operation in progress, so a crash (often out-of-memory) can be explained next launch. */
    private inline fun <T> guarded(what: String, block: () -> T): T {
        runGuard.writeText(what)
        try { return block() } finally { runGuard.delete() }
    }

    /** If the app died during a model operation last time, a plain-language note about it (shown once). */
    fun consumeCrashNote(): String? {
        val f = runGuard
        if (!f.exists()) return null
        val what = runCatching { f.readText() }.getOrDefault("running the model")
        f.delete()
        return "Outpost closed unexpectedly while $what last time. That usually means the phone ran out of memory. " +
            "Try a smaller model, or set the context window to 2K in Settings."
    }

    fun stop() {
        val h = chatHandle
        if (h != 0L) NativeLlm.nativeStop(h)
    }

    suspend fun countTokens(text: String): Int = withContext(thread) {
        if (chatHandle == 0L) text.length / 4 else NativeLlm.nativeCountTokens(chatHandle, text)
    }

    val contextSize: Int get() = chatCtx

    suspend fun ensureEmbedModel(file: File): Unit = withContext(thread) {
        check(available) { NativeLlm.loadError ?: "On-device AI unavailable" }
        if (embedHandle != 0L && embedPath == file.absolutePath) return@withContext
        if (embedHandle != 0L) NativeLlm.nativeFree(embedHandle)
        embedHandle = NativeLlm.nativeLoad(file.absolutePath, 2048, 0, true)
        embedPath = if (embedHandle != 0L) file.absolutePath else null
        if (embedHandle == 0L) error("Couldn't load the search model ${file.name}.")
    }

    suspend fun embed(text: String): FloatArray? = withContext(thread) {
        if (embedHandle == 0L) null else NativeLlm.nativeEmbed(embedHandle, text)
    }

    suspend fun unloadAll() = withContext(thread) {
        unloadChatLocked()
        if (embedHandle != 0L) NativeLlm.nativeFree(embedHandle)
        embedHandle = 0L
        embedPath = null
    }

    private fun unloadChatLocked() {
        if (chatHandle != 0L) NativeLlm.nativeFree(chatHandle)
        chatHandle = 0L
        chatPath = null
        chatCtx = 0
    }

    suspend fun systemInfo(): String = if (checkAvailable()) NativeLlm.nativeSystemInfo() + (if (NativeLlm.safeMode) " (safe mode)" else "") else (unavailableReason ?: "")

    fun resetCrashState() { NativeLlm.reset(context) }
}
