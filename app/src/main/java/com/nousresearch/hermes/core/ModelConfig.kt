package com.nousresearch.hermes.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Model + provider configuration.
 *
 * Ported from Hermes' provider/model settings: base URL, API key, model id, plus the
 * generation knobs the agent loop needs. Stored in SharedPreferences so the APK works
 * without any config file on the device.
 */
data class ModelConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val systemPrompt: String = "",
    /**
     * When set, used verbatim as the chat endpoint (no `/v1` or `/chat/completions`
     * appended). Lets the user point at an arbitrary relay path, e.g.
     * `https://host/api/openai/chat/completions` or a gateway with a custom prefix.
     */
    val customChatUrl: String = "",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * Resolved chat-completions endpoint.
     *
     * If the user configured a full endpoint it wins outright; otherwise the base URL is
     * normalized (tolerating `https://host`, `https://host/v1`, `…/v1/`) and
     * `/chat/completions` appended.
     */
    val chatCompletionsUrl: String
        get() = chatUrlCandidates().first()

    /** Chat endpoints to try, in preference order. */
    fun chatUrlCandidates(): List<String> {
        if (customChatUrl.trim().isNotEmpty()) return listOf(customChatUrl)
        val normalized = normalizeBase(baseUrl) + "/chat/completions"
        val rawBase = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        return listOf(normalized, "$rawBase/chat/completions").distinct()
    }

    /**
     * Base used to build the `/models` listing URL. Derived from the resolved chat
     * endpoint so a custom path keeps its prefix (…/v1/models next to …/v1/chat/…).
     */
    val modelsBaseUrl: String
        get() {
            val chat = chatCompletionsUrl
            return if (chat.endsWith("/chat/completions")) {
                chat.removeSuffix("/chat/completions")
            } else {
                // Custom, non-standard endpoint: fall back to the normalized base.
                normalizeBase(baseUrl)
            }
        }

    /** Model-list endpoints corresponding to the chat endpoint candidates. */
    fun modelsCandidates(): List<String> {
        if (customChatUrl.trim().isNotEmpty()) return listOf("$modelsBaseUrl/models")
        val normalizedBase = normalizeBase(baseUrl)
        val rawBase = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        return listOf("$normalizedBase/models", "$rawBase/models").distinct()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val PREFS = "hermes_config"
        private const val K_BASE = "base_url"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_TEMP = "temperature"
        private const val K_MAXTOK = "max_tokens"
        private const val K_SYS = "system_prompt"
        private const val K_CUSTOM_URL = "custom_chat_url"

        fun normalizeBase(raw: String): String {
            var b = raw.trim().trimEnd('/')
            if (b.isEmpty()) b = DEFAULT_BASE_URL
            // Accept "https://host", "https://host/v1" and "…/chat/completions" alike.
            if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
            val afterScheme = b.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isNotEmpty() && !afterScheme.contains('/')) b = "$b/v1"
            return b
        }

        /**
         * Validates a user-supplied endpoint. Returns null when acceptable, else a
         * human-readable reason — surfaced in the settings screen instead of failing
         * later as an opaque network error.
         */
        fun validateChatUrl(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null // empty = use base URL derivation
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                return "必须以 http:// 或 https:// 开头"
            }
            val afterScheme = s.substringAfter("://")
            if (afterScheme.isBlank() || !afterScheme.contains('/')) {
                // No path at all — likely just a host; that's what base URL is for.
                return "请填写到完整路径，例如 https://host/v1/chat/completions" +
                    "（只填域名请改用上面的 Base URL）"
            }
            if (s.contains(' ')) return "地址中不能包含空格"
            return null
        }

        private fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): ModelConfig {
            val p = prefs(ctx)
            return ModelConfig(
                baseUrl = p.getString(K_BASE, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                apiKey = p.getString(K_KEY, "") ?: "",
                model = p.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                temperature = p.getString(K_TEMP, "0.7")?.toDoubleOrNull() ?: 0.7,
                maxTokens = p.getString(K_MAXTOK, "4096")?.toIntOrNull() ?: 4096,
                systemPrompt = p.getString(K_SYS, "") ?: "",
                customChatUrl = p.getString(K_CUSTOM_URL, "") ?: "",
            )
        }

        fun save(ctx: Context, cfg: ModelConfig) {
            prefs(ctx).edit().apply {
                putString(K_BASE, cfg.baseUrl)
                putString(K_KEY, cfg.apiKey)
                putString(K_MODEL, cfg.model)
                putString(K_TEMP, cfg.temperature.toString())
                putString(K_MAXTOK, cfg.maxTokens.toString())
                putString(K_SYS, cfg.systemPrompt)
                putString(K_CUSTOM_URL, cfg.customChatUrl)
            }.apply()
        }
    }
}

/**
 * Chat-completions request body builder (OpenAI wire format — also accepted by
 * OpenRouter, DeepSeek, Moonshot, SiliconFlow, NewAPI relays, Ollama, vLLM, …).
 */
object ChatRequest {
    fun build(
        cfg: ModelConfig,
        messages: List<ChatMessage>,
        tools: List<JSONObject>,
        stream: Boolean,
    ): JSONObject {
        val o = JSONObject()
        o.put("model", cfg.model)
        o.put("temperature", cfg.temperature)
        o.put("max_tokens", cfg.maxTokens)
        o.put("stream", stream)

        val arr = org.json.JSONArray()
        messages.forEach { arr.put(it.toJson()) }
        o.put("messages", arr)

        if (tools.isNotEmpty()) {
            val t = org.json.JSONArray()
            tools.forEach { t.put(it) }
            o.put("tools", t)
            o.put("tool_choice", "auto")
        }
        return o
    }
}
