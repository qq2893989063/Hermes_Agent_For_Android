package com.nousresearch.hermes.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/** Events surfaced to the UI as the agent works. */
sealed class AgentEvent {
    data class Text(val text: String) : AgentEvent()
    data class Thinking(val text: String) : AgentEvent()
    data class ToolStart(val name: String, val args: String) : AgentEvent()
    data class ToolEnd(val name: String, val result: String) : AgentEvent()
    data class Notice(val text: String) : AgentEvent()
    data class Error(val text: String) : AgentEvent()
    data object TurnDone : AgentEvent()
}

/**
 * The agent loop — Hermes' core architecture, ported to Kotlin.
 *
 * Shape preserved from the original (see `run_agent.py::AIAgent`):
 *   call model -> if it requested tools, run them, append results, loop again;
 *   otherwise stream the answer out and finish.
 * Guarded by a hard iteration ceiling so a tool-calling model cannot spin forever.
 */
class Agent(
    private val context: Context,
    private val cfg: ModelConfig,
    private val maxIterations: Int = 12,
) {
    private val client = LlmClient(cfg)
    private val history = mutableListOf<ChatMessage>()

    /** Clears conversation state (new session). */
    fun reset() {
        history.clear()
    }

    /** Renders the current transcript for persistence/debugging. */
    fun transcript(): List<ChatMessage> = history.toList()

    /** Seed prior turns, e.g. restoring a saved session. */
    fun seed(messages: List<ChatMessage>) {
        history.clear()
        history.addAll(messages)
    }

    private fun systemPrompt(): String {
        val memory = MemoryStore.render(context)
        val skills = SkillRegistry.indexForPrompt(context)
        val custom = cfg.systemPrompt.trim()
        return buildString {
            append(Soul.IDENTITY)
            append("\n\n")
            append(Soul.BEHAVIOUR)
            if (custom.isNotEmpty()) {
                append("\n\n## 用户自定义指令\n")
                append(custom)
            }
            if (skills.isNotEmpty()) {
                append("\n\n")
                append(skills)
            }
            if (memory.isNotEmpty()) {
                append("\n\n")
                append(memory)
            }
            append("\n\n")
            append(Soul.DEVICE_CONTEXT)
        }
    }

    /**
     * Run one user turn to completion, emitting incremental events.
     * Tool calls are executed inline; their results are appended as `tool` messages.
     */
    fun run(userText: String): Flow<AgentEvent> = flow {
        if (!cfg.isConfigured) {
            emit(AgentEvent.Error("请先在设置中配置 API Key 与模型。"))
            return@flow
        }

        history.add(ChatMessage.user(userText))

        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            val messages = ArrayList<ChatMessage>(history.size + 1)
            messages.add(ChatMessage.system(systemPrompt()))
            messages.addAll(history)

            val tools = ToolRegistry.available().map { it.schema() }
            var assistantText = StringBuilder()
            var pendingCalls: List<ToolCall> = emptyList()
            var failure: String? = null

            client.streamChat(messages, tools).collect { ev ->
                when (ev) {
                    is StreamEvent.TextDelta -> {
                        assistantText.append(ev.text)
                        emit(AgentEvent.Text(ev.text))
                    }
                    is StreamEvent.ThinkingDelta -> emit(AgentEvent.Thinking(ev.text))
                    is StreamEvent.ToolCalls -> pendingCalls = ev.calls
                    is StreamEvent.Failure -> failure = ev.message
                    is StreamEvent.Done -> Unit
                }
            }

            if (failure != null) {
                emit(AgentEvent.Error(failure!!))
                return@flow
            }

            val text = assistantText.toString()
            if (pendingCalls.isEmpty()) {
                history.add(ChatMessage.assistant(text.ifBlank { null }))
                emit(AgentEvent.TurnDone)
                return@flow
            }

            // Model wants tools: record its request, execute, feed results back.
            history.add(
                ChatMessage(
                    role = "assistant",
                    content = text.ifBlank { null },
                    toolCalls = pendingCalls,
                ),
            )
            history.add(ChatMessage.system(ToolLoop.NUDGE))

            for (call in pendingCalls) {
                emit(AgentEvent.ToolStart(call.name, call.arguments))
                val result = ToolRegistry.dispatch(call)
                emit(AgentEvent.ToolEnd(call.name, result))
                history.add(ChatMessage.tool(call.id, result))
            }
        }

        emit(AgentEvent.Notice("已达到最大工具调用轮次（$maxIterations），停止本轮。"))
        emit(AgentEvent.TurnDone)
    }.flowOn(Dispatchers.IO)

    /** One-shot summarization used by the session-title path. */
    suspend fun titleFor(userText: String): String = runCatching {
        val res = client.complete(
            listOf(
                ChatMessage.system("用不超过 6 个汉字概括用户意图，只输出标题本身，不要标点。"),
                ChatMessage.user(userText),
            ),
            emptyList(),
        )
        res.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
    }.getOrDefault("")

    /** Probes the configured endpoint so the settings screen can validate credentials. */
    suspend fun testConnection(): String = runCatching {
        val models = client.listModels()
        if (models.isEmpty()) "连接成功（未返回模型列表）" else "连接成功，可用模型 ${models.size} 个"
    }.getOrElse { "连接失败: ${it.message}" }

    fun availableModels(): Flow<List<String>> = flow {
        emit(runCatching { client.listModels() }.getOrDefault(emptyList()))
    }.flowOn(Dispatchers.IO)

    /** Exposed so the settings screen can show what the model currently sees. */
    fun currentSystemPrompt(): String = systemPrompt()

    /** Tools currently exposed, grouped by toolset (for the tool list UI). */
    fun toolInventory(): Map<String, List<String>> = ToolRegistry.byToolset()

    companion object {
        /** Byte size of the rendered prompt, for the context meter. */
        fun promptBytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

        fun prettyArgs(raw: String): String = runCatching {
            JSONObject(raw).toString(2)
        }.getOrDefault(raw)
    }
}
