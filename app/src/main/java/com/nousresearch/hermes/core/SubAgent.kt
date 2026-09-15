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

/**
 * Wall-clock budget for one child.
 *
 * 120s was too tight: a child doing real web research (search -> fetch -> search again)
 * routinely needs 5-10 tool calls, and search alone costs up to 8s per engine attempt
 * because the providers are tried in series. Measured on device: a research child hit
 * 116s and was cut off, which the parent then reported as a generic empty response.
 */
private const val CHILD_TIMEOUT_MS = 300_000L

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
    // Research children need many rounds (search, fetch, refine, repeat). Measured on
    // device: a real news-research child used 10 tool calls, so 40 is a floor, not a target.
    maxIterations: Int = 40,
    onProgress: ((String) -> Unit)? = null,
): SubAgentResult {
    val started = System.currentTimeMillis()
    var toolCalls = 0
    var childError: String? = null
    val summary = StringBuilder()
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
        withTimeout(CHILD_TIMEOUT_MS) {
            child.run(goal).collect { event ->
                when (event) {
                    is AgentEvent.Text -> summary.append(event.text)
                    is AgentEvent.ToolStart -> { toolCalls++; onProgress?.invoke("${event.name} start") }
                    is AgentEvent.ToolEnd -> onProgress?.invoke("${event.name} done")
                    is AgentEvent.Error -> {
                        // An error event means the child never produced a final answer. Track it
                        // so the result reports ok=false instead of a happy status with an error
                        // message sitting in `summary` (which the parent would read as success).
                        childError = event.text
                        summary.append("\n").append(event.text)
                    }
                    is AgentEvent.Notice -> summary.append("\n").append(event.text)
                    else -> Unit
                }
            }
        }
        // Report rounds actually taken, not the ceiling. A child that only produced an error
        // still reports ok=false; but if it gathered tool output, keep that as the summary so
        // the parent gets something usable instead of a bare failure.
        val collected = summary.toString().trim()
        val fallback = child.salvagedText()
        SubAgentResult(
            0,
            goal,
            boundedSummary(collected.ifEmpty { fallback }),
            childError == null && collected.isNotEmpty(),
            child.lastIterations,
            toolCalls,
            System.currentTimeMillis() - started,
            childError,
        )
    } catch (e: TimeoutCancellationException) {
        // A timeout is not the same as "the model returned nothing": the child may well have
        // gathered real findings over its tool calls. Say what actually happened, include the
        // round/call counts, and keep any partial text so the parent can still use it.
        val partial = summary.toString().trim()
        val why = "子agent超时（${CHILD_TIMEOUT_MS / 1000}秒，已用 ${toolCalls} 次工具调用）"
        SubAgentResult(
            0,
            goal,
            boundedSummary(partial.ifEmpty { why }),
            false,
            0,
            toolCalls,
            System.currentTimeMillis() - started,
            why,
        )
    } catch (e: Throwable) {
        SubAgentResult(0, goal, boundedSummary(summary.toString().trim()), false, 0, toolCalls, System.currentTimeMillis() - started, e.message ?: e.javaClass.simpleName)
    }
}
