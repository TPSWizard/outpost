package com.conza.outpost.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * NOMAD's "use a model on another machine" option: talks to Ollama (`/api/chat`) or any
 * OpenAI-compatible server (LM Studio, llama.cpp server, vLLM) on your network.
 */
class RemoteLlm(baseClient: OkHttpClient) {
    private val http = baseClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES) // first token can be slow while the server loads a model
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    suspend fun chat(
        api: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        messages: List<Pair<String, String>>,
        temperature: Float,
        onToken: (String) -> Boolean,
    ): GenerationStats = withContext(Dispatchers.IO) {
        val msgs = buildJsonArray {
            messages.forEach { (role, content) -> add(buildJsonObject { put("role", role); put("content", content) }) }
        }
        val (url, body) = if (api == "openai") {
            "$baseUrl/v1/chat/completions" to buildJsonObject {
                put("model", model); put("messages", msgs); put("stream", true); put("temperature", temperature)
            }
        } else {
            "$baseUrl/api/chat" to buildJsonObject {
                put("model", model); put("messages", msgs); put("stream", true)
                put("options", buildJsonObject { put("temperature", temperature) })
            }
        }
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()

        var count = 0
        val start = System.currentTimeMillis()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Server answered HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            val src = resp.body?.source() ?: error("Empty response")
            while (!src.exhausted()) {
                currentCoroutineContext().ensureActive()
                val line = src.readUtf8Line() ?: break
                val piece = parseLine(api, line) ?: continue
                if (piece === DONE) break
                if (piece.isEmpty()) continue
                count++
                if (!onToken(piece)) break
            }
        }
        GenerationStats(count, System.currentTimeMillis() - start)
    }

    private val DONE = String(charArrayOf('\u0000'))

    private fun parseLine(api: String, raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        return if (api == "openai") {
            if (!line.startsWith("data:")) return null
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") return DONE
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        } else {
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
            obj["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
            if (obj["done"]?.jsonPrimitive?.booleanOrNull == true) return DONE
            (obj["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    /** Lists model names on the server, for the settings screen's "Test connection". */
    suspend fun listModels(api: String, baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val url = if (api == "openai") "$baseUrl/v1/models" else "$baseUrl/api/tags"
        val req = Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
        http.newBuilder().readTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (api == "openai") obj["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
                else obj["models"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            }
    }
}
