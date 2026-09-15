package com.nousresearch.hermes.core

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.min

data class SubAgentResult(
    val taskIndex: Int,
    val goal: String,
    val summary: String,
    val ok: Boolean,
    val iterations: Int,
    val toolCalls: Int,
    val elapsedMs: Long,
    val error: String? = null,
)

private const val CHILD_SYSTEM_PROMPT =
    "You are a focused sub-agent. You cannot talk to the user. Work only on the supplied goal and context, do not ask questions, and finish with a concise summary of findings and actions."

private const val SUMMARY_LIMIT = 4000

private fun boundedSummary(text: String): String {
    if (text.length <= SUMMARY_LIMIT) return text
    val head = SUMMARY_LIMIT / 2
    val tail = SUMMARY_LIMIT - head - 64
    return text.take(head) + "\n...[summary truncated, ${text.length} chars total]...\n" + text.takeLast(tail.coerceAtLeast(0))
}

suspend fun runChild(
    appContext: Context,
    cfg: ModelConfig,
    goal: String,
    background: String?,
    maxIterations: Int = 40,
    onProgress: ((String) -> Unit)? = null,
): SubAgentResult {
    val started = System.currentTimeMillis()
    var toolCalls = 0
    var summary = StringBuilder()
    return try {
        val prompt = buildString {
            append(CHILD_SYSTEM_PROMPT)
            append("\n\nGOAL:\n")
            append(goal)
            if (!background.isNullOrBlank()) {
                append("\n\nCONTEXT:\n")
                append(background)
            }
        }
        // Children cannot delegate or mutate shared state; this guard is intentional.
        val child = Agent(
            appContext,
            cfg,
            maxIterations,
            blockedTools = setOf("delegate_task", "memory", "todo"),
            systemPromptOverride = prompt,
        )
        withTimeout(120_000L) {
            child.run(goal).collect { event ->
                when (event) {
                    is AgentEvent.Text -> summary.append(event.text)
                    is AgentEvent.ToolStart -> { toolCalls++; onProgress?.invoke("${event.name} start") }
                    is AgentEvent.ToolEnd -> onProgress?.invoke("${event.name} done")
                    is AgentEvent.Error -> summary.append("\n").append(event.text)
                    is AgentEvent.Notice -> summary.append("\n").append(event.text)
                    else -> Unit
                }
            }
        }
        // Report rounds actually taken, not the ceiling.
        SubAgentResult(0, goal, boundedSummary(summary.toString().trim()), true, child.lastIterations, toolCalls, System.currentTimeMillis() - started)
    } catch (e: TimeoutCancellationException) {
        SubAgentResult(0, goal, boundedSummary(summary.toString().trim()), false, 0, toolCalls, System.currentTimeMillis() - started, "子agent超时（120秒）")
    } catch (e: Throwable) {
        SubAgentResult(0, goal, boundedSummary(summary.toString().trim()), false, 0, toolCalls, System.currentTimeMillis() - started, e.message ?: e.javaClass.simpleName)
    }
}
