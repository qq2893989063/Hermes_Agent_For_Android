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
    private val blockedTools: Set<String> = emptySet(),
    private val systemPromptOverride: String? = null,
) {
    private val client = LlmClient(cfg)
    private val history = mutableListOf<ChatMessage>()

    /**
     * Model round-trips actually performed in the last [run]. Distinct from
     * [maxIterations], which is only the ceiling — reporting the ceiling as if it were the
     * real count would misreport a one-shot answer as a 40-round tool loop.
     */
    var lastIterations: Int = 0
        private set

    /**
     * How many times to ask "write up your findings" after tools have run but the model
     * produced no text. More than one, because a busy gateway can return empty twice in a
     * row; capped, so an unresponsive model cannot spin forever.
     */
    private val MAX_FINAL_ATTEMPTS = 3

    /**
     * Last resort when the model will not write a summary: render the tool results we already
     * have. Returning an error instead would discard real work the user paid for, and that
     * error was not actionable anyway.
     */
    private fun salvageFromToolResults(): String {
        val results = history.filter { it.role == "tool" }
        if (results.isEmpty()) return ""
        val body = results.joinToString("\n\n") { msg ->
            val raw = msg.content.orEmpty().trim()
            raw.take(600) + if (raw.length > 600) " …" else ""
        }
        return "（模型未能生成总结，以下为已获取到的工具结果原文）\n\n$body"
    }

    /**
     * Whatever the tool round produced, for callers that would otherwise discard the turn
     * (a sub-agent whose model went silent, for example).
     */
    fun salvagedText(): String = salvageFromToolResults()

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
        systemPromptOverride?.let { return it }
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
        lastIterations = 0
        // Guards the "please write up your findings" retry, so a model that keeps returning
        // nothing cannot loop forever. More than one attempt is needed: the first retry can
        // itself come back empty on a busy gateway.
        var finalAttempts = 0
        // Guidance to fold into the next user turn (never a separate message).
        var pendingGuidance: String? = null
        while (iteration < maxIterations) {
            iteration++
            lastIterations = iteration

            // Fold any pending guidance into a single user turn. Appending it as its own
            // message produced two consecutive `user` turns, which some gateways answer with
            // an empty completion -- defeating the very retry added to recover from silence.
            if (pendingGuidance != null) {
                history.add(ChatMessage.user(pendingGuidance!!))
                pendingGuidance = null
            }

            val messages = ArrayList<ChatMessage>(history.size + 1)
            messages.add(ChatMessage.system(systemPrompt()))
            messages.addAll(history)

            val tools = ToolRegistry.available()
                .filterNot { it.name in blockedTools }
                .map { it.schema() }
            var assistantText = StringBuilder()
            var pendingCalls: List<ToolCall> = emptyList()
            var failure: String? = null

            client.chatWithFallback(messages, tools).collect { ev ->
                when (ev) {
                    is StreamEvent.TextDelta -> {
                        assistantText.append(ev.text)
                        emit(AgentEvent.Text(ev.text))
                    }
                    is StreamEvent.ThinkingDelta -> emit(AgentEvent.Thinking(ev.text))
                    is StreamEvent.ToolCalls -> pendingCalls = ev.calls
                    is StreamEvent.Truncated -> {
                        // chatWithFallback already retried with a non-streaming call, so this
                        // only surfaces if the retry also failed to produce anything.
                        failure = "流式响应被中断且重试无效（${ev.detail}）"
                    }
                    is StreamEvent.Failure -> failure = ev.message
                    is StreamEvent.Done -> Unit
                }
            }

            // A stream-level failure is only decisive when this round produced nothing usable.
            // The model often ends a tool round with just a finish_reason and no text (very
            // common after several tool calls): LlmClient reports that as a Failure, but the
            // round's tool results are already in `history`. If we have results in hand, ask
            // once more for the write-up instead of discarding the whole turn.
            val text = assistantText.toString()
            val haveToolResults = history.any { it.role == "tool" }
            if (failure != null && text.isBlank() && pendingCalls.isEmpty()) {
                if (haveToolResults && finalAttempts < MAX_FINAL_ATTEMPTS) {
                    finalAttempts++
                    pendingGuidance = ToolLoop.FINAL_ANSWER_PROMPT
                    continue
                }
                if (haveToolResults) {
                    // Out of retries. The tool output is real work, so hand it over rather
                    // than discarding it behind an error the user cannot act on.
                    emit(AgentEvent.Text(salvageFromToolResults()))
                } else {
                    emit(AgentEvent.Error(failure!!))
                }
                emit(AgentEvent.TurnDone)
                return@flow
            }

            if (pendingCalls.isEmpty()) {
                if (text.isBlank()) {
                    // Model stopped with neither text nor tools. If tools already ran, prompt
                    // for the summary; otherwise this really is an empty response.
                    if (haveToolResults && finalAttempts < MAX_FINAL_ATTEMPTS) {
                        finalAttempts++
                        pendingGuidance = ToolLoop.FINAL_ANSWER_PROMPT
                        continue
                    }
                    if (haveToolResults) {
                        emit(AgentEvent.Text(salvageFromToolResults()))
                    } else {
                        emit(
                            AgentEvent.Error(
                                if (iteration > 1) {
                                    "模型在工具调用后没有返回任何内容，本轮结束。"
                                } else {
                                    "模型返回了空响应，请重试或检查模型与接口地址是否匹配。"
                                },
                            ),
                        )
                    }
                    emit(AgentEvent.TurnDone)
                    return@flow
                }
                history.add(ChatMessage.assistant(text))
                emit(AgentEvent.TurnDone)
                return@flow
            }

            // Model wants tools: record its request, execute, feed results back.
            history.add(
                ChatMessage(
                    role = "assistant",
                    // Send "" rather than null: several gateways (incl. NewAPI-style
                    // proxies) mishandle a null content on a tool-calling assistant turn.
                    content = text.ifBlank { "" },
                    toolCalls = pendingCalls,
                ),
            )

            // NOTE: the ToolLoop nudge must NOT go here. The OpenAI protocol requires every
            // `tool` message to immediately follow the `assistant` turn that requested it,
            // and `system` messages to sit at the front. Inserting a system message between
            // them produced a malformed sequence that some gateways answer with an empty
            // completion -- which surfaced to the user as "模型返回了空响应" on any tool-using
            // turn (sub-agents doing research hit it every time).
            for (call in pendingCalls) {
                emit(AgentEvent.ToolStart(call.name, call.arguments))
                val result = ToolRegistry.dispatch(call)
                emit(AgentEvent.ToolEnd(call.name, result))
                history.add(ChatMessage.tool(call.id, result))
            }

            // Guidance for the next round. It must be merged into the FOLLOWING user turn
            // rather than appended as its own message: two consecutive `user` messages make
            // some gateways answer with another empty completion, which is why the
            // final-answer retry below used to fail no matter how often it ran.
            pendingGuidance = ToolLoop.NUDGE
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
