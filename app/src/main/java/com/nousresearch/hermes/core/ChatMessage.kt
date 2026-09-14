package com.nousresearch.hermes.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible chat message.
 *
 * Mirrors Hermes' internal message envelope: role + content, where content may be a
 * plain string or a multimodal parts array (text + image_url), plus the tool-call
 * fields needed for the agent loop.
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val parts: List<ContentPart> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        when {
            parts.isNotEmpty() -> {
                val arr = JSONArray()
                parts.forEach { arr.put(it.toJson()) }
                o.put("content", arr)
            }
            content != null -> o.put("content", content)
            else -> o.put("content", JSONObject.NULL)
        }
        if (toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            toolCalls.forEach { arr.put(it.toJson()) }
            o.put("tool_calls", arr)
        }
        if (toolCallId != null) o.put("tool_call_id", toolCallId)
        if (name != null) o.put("name", name)
        return o
    }

    companion object {
        fun user(text: String) = ChatMessage(role = "user", content = text)
        fun system(text: String) = ChatMessage(role = "system", content = text)
        fun assistant(text: String?) = ChatMessage(role = "assistant", content = text)
        fun tool(id: String, result: String) =
            ChatMessage(role = "tool", content = result, toolCallId = id)
    }
}

/** A single part of a multimodal content array. */
sealed class ContentPart {
    abstract fun toJson(): JSONObject

    /** Plain text. */
    data class Text(val text: String) : ContentPart() {
        override fun toJson() = JSONObject().apply {
            put("type", "text")
            put("text", text)
        }
    }

    /** Inline base64 image or remote URL — the vision path for `read_image`. */
    data class Image(val dataUrl: String) : ContentPart() {
        override fun toJson() = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", dataUrl))
        }
    }
}

/** A model-requested tool invocation. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            },
        )
    }

    /** Parsed arguments, tolerating empty/malformed JSON. */
    fun args(): JSONObject = runCatching {
        if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
    }.getOrElse { JSONObject() }

    companion object {
        fun fromJson(o: JSONObject): ToolCall {
            val fn = o.optJSONObject("function") ?: JSONObject()
            return ToolCall(
                id = o.optString("id"),
                name = fn.optString("name"),
                arguments = fn.optString("arguments", "{}"),
            )
        }
    }
}
