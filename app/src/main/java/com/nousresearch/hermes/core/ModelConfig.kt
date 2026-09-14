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
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** Chat-completions endpoint, tolerating a base URL with or without `/v1`. */
    val chatCompletionsUrl: String get() = normalizeBase(baseUrl) + "/chat/completions"

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

        fun normalizeBase(raw: String): String {
            var b = raw.trim().trimEnd('/')
            if (b.isEmpty()) b = DEFAULT_BASE_URL
            // Accept "https://host", "https://host/v1" and "…/chat/completions" alike.
            if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
            if (!b.endsWith("/v1") && !b.contains("/v1/")) b = "$b/v1"
            return b
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
