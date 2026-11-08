package com.aura.launcher.sketch

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SketchProvider(val label: String, val baseUrl: String, val defaultModel: String) {
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "claude-sonnet-5-5"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash"),
    /** Anything that speaks OpenAI's chat completions, at its own address. */
    CUSTOM("Outro (compatível com OpenAI)", "", ""),
}

/** The model that watches the board. Kept on the phone, in its own file, never in a backup. */
data class SketchModelConfig(
    val provider: SketchProvider = SketchProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
) {
    val effectiveModel: String get() = model.ifBlank { provider.defaultModel }
    val effectiveBaseUrl: String get() = baseUrl.ifBlank { provider.baseUrl }.trimEnd('/')
    val ready: Boolean get() = apiKey.isNotBlank() && effectiveModel.isNotBlank() && effectiveBaseUrl.isNotBlank()
}

class SketchModel(context: Context) {
    private val prefs = context.getSharedPreferences("sketch_model", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<SketchModelConfig> = _config.asStateFlow()

    private fun load() = SketchModelConfig(
        provider = SketchProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: SketchProvider.ANTHROPIC,
        apiKey = prefs.getString("key", "").orEmpty(),
        model = prefs.getString("model", "").orEmpty(),
        baseUrl = prefs.getString("base", "").orEmpty(),
    )

    fun update(next: SketchModelConfig) {
        prefs.edit()
            .putString("provider", next.provider.name)
            .putString("key", next.apiKey.trim())
            .putString("model", next.model.trim())
            .putString("base", next.baseUrl.trim())
            .apply()
        _config.value = next
    }

    /** One look: [system], then a picture (JPEG) and [text]. The reply's text. */
    suspend fun ask(system: String, jpeg: ByteArray, text: String): String {
        val c = config.value
        check(c.ready) { "Sem modelo configurado" }
        val image = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val request = when (c.provider) {
            SketchProvider.ANTHROPIC -> Request.Builder()
                .url(anthropic(c, "messages"))
                .header("x-api-key", c.apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(buildJsonObject {
                    put("model", c.effectiveModel)
                    put("max_tokens", 4096)
                    put("system", system)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64"); put("media_type", "image/jpeg"); put("data", image)
                                    })
                                })
                                add(buildJsonObject { put("type", "text"); put("text", text) })
                            })
                        })
                    })
                }.toString().toRequestBody(JSON))
                .build()

            else -> Request.Builder()
                .url("${c.effectiveBaseUrl}/chat/completions")
                .header("Authorization", "Bearer ${c.apiKey}")
                // Azure takes its key here; everybody else ignores it.
                .header("api-key", c.apiKey)
                .post(buildJsonObject {
                    put("model", c.effectiveModel)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", system) })
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$image") })
                                })
                                add(buildJsonObject { put("type", "text"); put("text", text) })
                            })
                        })
                    })
                }.toString().toRequestBody(JSON))
                .build()
        }
        val body = withContext(Dispatchers.IO) { client.newCall(request).await() }
        val root = Json.parseToJsonElement(body).jsonObject
        return when (c.provider) {
            SketchProvider.ANTHROPIC -> root["content"]!!.jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
            else -> root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"].let {
                when (it) {
                    is JsonPrimitive -> it.content
                    is JsonArray -> it.joinToString("") { p -> p.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
                    else -> ""
                }
            }
        }
    }

    /**
     * The models this address offers, for picking rather than typing. On
     * Azure that is its deployments — the names a request is sent to — not
     * the catalogue its /models lists; on OpenRouter, only those that see.
     */
    suspend fun models(): List<String> {
        val c = config.value
        check(c.apiKey.isNotBlank() && c.effectiveBaseUrl.isNotBlank())
        val base = c.effectiveBaseUrl
        val host = runCatching { java.net.URI(base) }.getOrNull()
        val azure = host?.host?.let { h -> AZURE_HOSTS.any { h.endsWith(it) } } == true
        val request = when {
            c.provider == SketchProvider.ANTHROPIC -> Request.Builder().url(anthropic(c, "models?limit=1000"))
                .header("x-api-key", c.apiKey).header("anthropic-version", "2023-06-01")
            azure -> Request.Builder().url("${host!!.scheme}://${host.host}/openai/deployments?api-version=2022-12-01")
                .header("api-key", c.apiKey)
            else -> Request.Builder().url("$base/models")
                .header("Authorization", "Bearer ${c.apiKey}").header("api-key", c.apiKey)
        }.get().build()
        val body = withContext(Dispatchers.IO) { client.newCall(request).await() }
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        return data.mapNotNull { m ->
            val o = m.jsonObject
            val inputs = o["architecture"]?.jsonObject?.get("input_modalities")?.jsonArray?.map { it.jsonPrimitive.content }
            if (inputs != null && "image" !in inputs) return@mapNotNull null
            o["id"]?.jsonPrimitive?.content
        }.distinct().sorted()
    }

    /**
     * One small look, to see the model answers and can see: a circle on white,
     * and what it says it is. The reply and how long it took.
     */
    suspend fun test(): Pair<String, Long> {
        val jpeg = withContext(Dispatchers.Default) {
            val bmp = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawCircle(80f, 80f, 50f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 8f; color = android.graphics.Color.BLACK
            })
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        val start = System.currentTimeMillis()
        val reply = ask("Answer in one or two words, in Portuguese.", jpeg, "What shape is drawn in this picture?")
        return reply.trim().take(60) to System.currentTimeMillis() - start
    }

    /** Anthropic's [path] under the address, whether it was given with its /v1 or without. */
    private fun anthropic(c: SketchModelConfig, path: String): String {
        val base = c.effectiveBaseUrl
        return if (base.endsWith("/v1")) "$base/$path" else "$base/v1/$path"
    }

    /** Cancelling the coroutine cancels the request: a stale look is not worth finishing. */
    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!cont.isActive) return
                    if (r.isSuccessful) cont.resume(text)
                    else cont.resumeWithException(IOException("HTTP ${r.code}: ${text.take(300)}"))
                }
            }
        })
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val AZURE_HOSTS = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
