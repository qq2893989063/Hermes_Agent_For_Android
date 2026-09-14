package com.nousresearch.hermes.core

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Session(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

/** File-backed history keeps large transcripts out of preferences and limits disk growth. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.json")

    fun list(): List<Session> = read().sortedByDescending { it.updatedAt }
    fun load(id: String): Session? = read().firstOrNull { it.id == id }

    @Synchronized fun save(session: Session) {
        val bounded = session.copy(messages = session.messages.takeLast(MAX_MESSAGES))
        val all = read().filterNot { it.id == session.id } + bounded
        write(all.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS))
    }

    fun delete(id: String) = write(read().filterNot { it.id == id })

    fun rename(id: String, title: String) {
        write(read().map { if (it.id == id) it.copy(title = title.trim().ifBlank { it.title }) else it })
    }

    private fun read(): List<Session> = runCatching {
        val array = if (file.exists()) JSONArray(file.readText(Charsets.UTF_8)) else JSONArray()
        (0 until array.length()).mapNotNull { parseSession(array.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(sessions: List<Session>) {
        val array = JSONArray()
        sessions.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id); put("title", s.title); put("updatedAt", s.updatedAt)
                put("messages", JSONArray().apply { s.messages.forEach { put(it.toJson()) } })
            })
        }
        runCatching { file.writeText(array.toString(), Charsets.UTF_8) }
    }

    private fun parseSession(o: JSONObject?): Session? = o?.let {
        val messages = it.optJSONArray("messages") ?: JSONArray()
        Session(it.optString("id", UUID.randomUUID().toString()), it.optString("title", "新会话"),
            it.optLong("updatedAt", 0), (0 until messages.length()).mapNotNull { n -> parseMessage(messages.optJSONObject(n)) })
    }

    private fun parseMessage(o: JSONObject?): ChatMessage? = o?.let {
        val content = it.opt("content")
        val parts = if (content is JSONArray) (0 until content.length()).mapNotNull { n ->
            val p = content.optJSONObject(n) ?: return@mapNotNull null
            if (p.optString("type") == "text") ContentPart.Text(p.optString("text"))
            else p.optJSONObject("image_url")?.optString("url")?.let(ContentPart::Image)
        } else emptyList()
        val calls = it.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).mapNotNull { n -> a.optJSONObject(n)?.let(ToolCall::fromJson) } } ?: emptyList()
        ChatMessage(it.optString("role"), if (content is String) content else null, calls,
            it.optString("tool_call_id").ifBlank { null }, it.optString("name").ifBlank { null }, parts)
    }

    companion object { const val MAX_SESSIONS = 50; const val MAX_MESSAGES = 200 }
}
