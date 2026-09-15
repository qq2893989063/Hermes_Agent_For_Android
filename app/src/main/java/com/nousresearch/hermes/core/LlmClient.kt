package com.nousresearch.hermes.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Incremental events emitted while streaming a completion. */
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
    data class Done(val finishReason: String?) : StreamEvent()
    data class Failure(val message: String) : StreamEvent()
}

/**
 * Streaming OpenAI-compatible chat client (SSE), plus a non-streaming fallback.
 *
 * The agent loop consumes [streamChat] as a Flow so the UI can render tokens as they
 * arrive and the loop can assemble tool calls without buffering the whole response.
 */
class LlmClient(private val cfg: ModelConfig) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(url: String, messages: List<ChatMessage>, tools: List<JSONObject>, stream: Boolean): Request {
        val body = ChatRequest.build(cfg, messages, tools, stream)
            .toString()
            .toRequestBody(JSON)
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body)
            .build()
    }

    /**
     * Stream a completion. Tool calls arrive as a single consolidated [StreamEvent.ToolCalls]
     * once the stream ends, because providers emit them as fragmented deltas.
     */
    fun streamChat(messages: List<ChatMessage>, tools: List<JSONObject>): Flow<StreamEvent> =
        callbackFlow {
            val candidates = cfg.chatUrlCandidates()
            val attempted = mutableListOf<String>()
            var activeCall: Call? = null
            // Accumulators for fragmented tool-call deltas, keyed by streamed index.
            val callIds = mutableMapOf<Int, String>()
            val callNames = mutableMapOf<Int, String>()
            val callArgs = mutableMapOf<Int, StringBuilder>()
            var finishReason: String? = null
            // Content arriving in ANY form, as opposed to merely well-formed SSE frames.
            // Tracking only frame arrival let a content-less response pass as a real answer,
            // so the turn ended with nothing rendered (the "sent but no reaction" defect).
            var sawContent = false
            var sawToolCall = false

            lateinit var enqueueCandidate: (Int) -> Unit
            enqueueCandidate = { candidateIndex ->
                val url = candidates[candidateIndex]
                attempted += url
                val call = http.newCall(request(url, messages, tools, stream = true))
                activeCall = call
                call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    trySendBlocking(StreamEvent.Failure("网络请求失败: ${e.message ?: e.javaClass.simpleName}"))
                    close()
                }

                override fun onResponse(c: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val err = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                            if ((resp.code == 404 || resp.code == 405) && candidateIndex + 1 < candidates.size) {
                                enqueueCandidate(candidateIndex + 1)
                                return
                            }
                            val suffix = if (resp.code == 404 || resp.code == 405) {
                                "; attempted: ${attempted.joinToString(", ")}" 
                            } else ""
                            trySendBlocking(
                                StreamEvent.Failure("HTTP ${resp.code}: ${summarize(err)}$suffix"),
                            )
                            close()
                            return
                        }
                        val source = resp.body?.source() ?: run {
                            trySendBlocking(StreamEvent.Failure("响应体为空"))
                            close()
                            return
                        }
                        try {
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isEmpty()) continue
                                if (payload == "[DONE]") break
                                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                                choice.optString("finish_reason")
                                    .takeIf { it.isNotEmpty() && it != "null" }
                                    ?.let { finishReason = it }

                                val delta = choice.optJSONObject("delta") ?: JSONObject()
                                delta.optString("content")
                                    .takeIf { it.isNotEmpty() && it != "null" }
                                    ?.let {
                                        sawContent = true
                                        trySendBlocking(StreamEvent.TextDelta(it))
                                    }
                                // Reasoning models expose chain-of-thought separately.
                                delta.optString("reasoning_content")
                                    .takeIf { it.isNotEmpty() && it != "null" }
                                    ?.let {
                                        sawContent = true
                                        trySendBlocking(StreamEvent.ThinkingDelta(it))
                                    }

                                delta.optJSONArray("tool_calls")?.let { tc ->
                                    if (tc.length() > 0) sawToolCall = true
                                    for (i in 0 until tc.length()) {
                                        val d = tc.optJSONObject(i) ?: continue
                                        val idx = d.optInt("index", i)
                                        d.optString("id").takeIf { it.isNotEmpty() }?.let {
                                            callIds[idx] = it
                                        }
                                        d.optJSONObject("function")?.let { fn ->
                                            fn.optString("name").takeIf { it.isNotEmpty() }
                                                ?.let { callNames[idx] = it }
                                            fn.optString("arguments").takeIf { it.isNotEmpty() }
                                                ?.let {
                                                    callArgs.getOrPut(idx) { StringBuilder() }.append(it)
                                                }
                                        }
                                    }
                                }
                            }
                            if (callIds.isNotEmpty() || callNames.isNotEmpty()) {
                                val calls = callIds.keys.union(callNames.keys).sorted().mapNotNull { idx ->
                                    val nm = callNames[idx] ?: return@mapNotNull null
                                    ToolCall(
                                        id = callIds[idx] ?: "call_$idx",
                                        name = nm,
                                        arguments = callArgs[idx]?.toString() ?: "{}",
                                    )
                                }
                                if (calls.isNotEmpty()) trySendBlocking(StreamEvent.ToolCalls(calls))
                            }
                            // Guard against a "successful" response that carried no usable
                            // content. Tracking only `sawAny` (any SSE data line) is not
                            // enough: a chunk with an empty delta or a bare finish_reason
                            // sets it, so a content-less turn passed as a real answer and
                            // the UI rendered nothing. Require actual content or tool calls.
                            val producedNothing = !sawContent && !sawToolCall
                            if (producedNothing) {
                                val why = if (finishReason != null) {
                                    "模型结束了回复但没有返回任何内容（finish_reason=$finishReason）"
                                } else {
                                    "模型返回了空响应"
                                }
                                trySendBlocking(StreamEvent.Failure(why))
                            } else {
                                trySendBlocking(StreamEvent.Done(finishReason))
                            }
                        } catch (t: Throwable) {
                            trySendBlocking(StreamEvent.Failure("解析流式响应失败: ${t.message}"))
                        } finally {
                            close()
                        }
                    }
                }
                })
            }
            enqueueCandidate(0)
            awaitClose { activeCall?.cancel() }
        }.flowOn(Dispatchers.IO)

    /** Non-streaming call — used when the provider rejects `stream: true`. */
    suspend fun complete(messages: List<ChatMessage>, tools: List<JSONObject>): JSONObject {
        val body = ChatRequest.build(cfg, messages, tools, stream = false).toString().toRequestBody(JSON)
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val candidates = cfg.chatUrlCandidates()
            val attempted = mutableListOf<String>()
            var lastError = ""
            for ((index, url) in candidates.withIndex()) {
                attempted += url
                http.newCall(request(url, messages, tools, stream = false)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return@withContext JSONObject(text)
                    lastError = "HTTP ${resp.code}: ${summarize(text)}"
                    if (resp.code != 404 && resp.code != 405) throw IOException(lastError)
                    if (index == candidates.lastIndex) {
                        throw IOException("$lastError; attempted: ${attempted.joinToString(", ")}")
                    }
                }
            }
            throw IOException("$lastError; attempted: ${attempted.joinToString(", ")}")
        }
    }

    /** Fetch the model list from an OpenAI-compatible `/models` endpoint. */
    suspend fun listModels(): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val candidates = cfg.modelsCandidates()
        var lastError = ""
        for ((index, url) in candidates.withIndex()) {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = "HTTP ${resp.code}"
                    if ((resp.code == 404 || resp.code == 405) && index < candidates.lastIndex) return@use
                    throw IOException(lastError)
                }
                val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull()
                if (data == null) {
                    if (index < candidates.lastIndex) return@use
                    return@withContext emptyList()
                }
                return@withContext (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                    .filter { it.isNotBlank() }
                    .sorted()
            }
        }
        if (lastError.isNotEmpty()) throw IOException(lastError)
        emptyList()
    }

    private fun summarize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(400).ifEmpty { "(空响应)" }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
