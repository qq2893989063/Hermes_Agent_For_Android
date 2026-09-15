package com.nousresearch.hermes.tools

import android.content.Context
import com.nousresearch.hermes.core.ModelConfig
import com.nousresearch.hermes.core.Schema
import com.nousresearch.hermes.core.SubAgentResult
import com.nousresearch.hermes.core.runChild
import com.nousresearch.hermes.core.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

object DelegateTools {
    private const val TOTAL_LIMIT = 12000

    fun register(context: Context) {
        ToolRegistry.register(
            name = "delegate_task",
            toolset = "agent",
            description = "分发任务给独立的子agent：子agent看不到当前对话的任何历史，" +
                "因此每个 goal 必须自成一体、写清具体要求；多个子任务共享的背景信息" +
                "要在各自的 context 里重复说明。只有每个子任务的最终摘要会返回给你，" +
                "中间的工具调用与推理过程不会占用你的上下文。最多同时执行 3 个子任务。",
            parameters = Schema.obj(
                properties = mapOf(
                    "tasks" to JSONObject().apply {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 3)
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("goal", Schema.string("该子agent要完成的任务，需具体且自包含"))
                                put("context", Schema.string("该子agent需要的背景信息，如文件路径、报错、约束条件；可选"))
                            })
                            put("required", JSONArray().put("goal"))
                            put("additionalProperties", false)
                        })
                    },
                ),
                required = listOf("tasks"),
            ),
            checkFn = { ModelConfig.load(context).isConfigured },
        ) { args ->
            val tasks = args.optJSONArray("tasks")
                ?: return@register ToolRegistry.errorJson("tasks 必须是数组")
            if (tasks.length() == 0) return@register ToolRegistry.errorJson("tasks 不能为空")
            if (tasks.length() > 3) return@register ToolRegistry.errorJson("tasks 最多包含 3 个任务")
            val cfg = ModelConfig.load(context)
            val results = coroutineScope {
                (0 until tasks.length()).map { index ->
                    val task = tasks.optJSONObject(index)
                        ?: return@map async { SubAgentResult(index, "", "", false, 0, 0, 0, "任务格式无效") }
                    async {
                        runChild(context, cfg, task.optString("goal").trim(), task.optString("context").ifBlank { null })
                            .copy(taskIndex = index)
                    }
                }.awaitAll()
            }
            render(results)
        }
    }

    private fun render(results: List<SubAgentResult>): String {
        val mutable = results.toMutableList()
        var json = buildJson(mutable)
        while (json.length > TOTAL_LIMIT) {
            val i = mutable.indices.maxByOrNull { mutable[it].summary.length } ?: break
            val r = mutable[i]
            if (r.summary.length <= 200) break
            mutable[i] = r.copy(summary = r.summary.take((r.summary.length * .7).toInt()) + "\n...[summary_truncated]", error = r.error)
            json = buildJson(mutable)
        }
        return json
    }

    private fun buildJson(results: List<SubAgentResult>): String = JSONObject().apply {
        put("count", results.size)
        put("results", JSONArray().apply { results.forEach { r -> put(JSONObject().apply {
            put("task_index", r.taskIndex); put("goal", r.goal); put("ok", r.ok)
            put("summary", r.summary); put("tool_calls", r.toolCalls)
            put("iterations", r.iterations); put("elapsed_ms", r.elapsedMs)
            put("error", r.error ?: JSONObject.NULL)
        }) } })
    }.toString()
}
