package com.nousresearch.hermes.tools

import android.content.Context
import com.nousresearch.hermes.core.MemoryStore
import com.nousresearch.hermes.core.Schema
import com.nousresearch.hermes.core.SkillRegistry
import com.nousresearch.hermes.core.ToolRegistry
import org.json.JSONObject

/**
 * Skill + memory tools — Hermes' learning loop, ported.
 *
 * `skills_list` / `skill_view` expose the bundled SKILL.md library; `memory` lets the
 * agent persist durable facts across sessions, which is the behaviour that makes
 * Hermes self-improving rather than a stateless chatbot.
 */
object SkillTools {

    fun register(context: Context) {
        ToolRegistry.register(
            name = "skills_list",
            toolset = "skills",
            description = "列出可用技能的索引（名称、描述、分类）。在不确定有哪些能力可用时先调用它。",
            parameters = Schema.obj(
                properties = mapOf(
                    "category" to Schema.string("按分类过滤，可留空表示全部"),
                ),
            ),
        ) { args ->
            SkillRegistry.listJson(context, args.optString("category").ifBlank { null })
        }

        ToolRegistry.register(
            name = "skill_view",
            toolset = "skills",
            description = "读取某个技能的完整内容（含详细步骤与示例）。" +
                "当任务与某个技能相关时，必须先读取它并严格按步骤执行。",
            parameters = Schema.obj(
                properties = mapOf(
                    "name" to Schema.string("技能名称，如 arxiv、maps、humanizer"),
                ),
                required = listOf("name"),
            ),
        ) { args ->
            val n = args.optString("name").trim()
            if (n.isEmpty()) return@register ToolRegistry.errorJson("name 不能为空")
            val skill = SkillRegistry.get(context, n)
                ?: return@register ToolRegistry.errorJson(
                    "未找到技能 '$n'。可用技能：" +
                        SkillRegistry.all(context).joinToString(", ") { it.name },
                )
            JSONObject().apply {
                put("name", skill.name)
                put("category", skill.category)
                put("content", skill.render())
            }.toString()
        }
    }
}

/** Cross-session memory, exposed as a tool. */
object MemoryTools {
    fun register(context: Context) {
        ToolRegistry.register(
            name = "memory",
            toolset = "memory",
            description = "长期记忆。保存关于用户的稳定偏好、环境事实或重要约定，" +
                "以便在以后的会话中记住。不要保存一次性的任务进度。",
            parameters = Schema.obj(
                properties = mapOf(
                    "action" to Schema.string(
                        "操作类型",
                        enum = listOf("add", "remove", "list", "clear"),
                    ),
                    "content" to Schema.string("要保存或删除的内容（action=add/remove 时必填）"),
                    "target" to Schema.string(
                        "存放位置：notes=普通笔记（默认），profile=用户画像",
                        enum = listOf("notes", "profile"),
                    ),
                ),
                required = listOf("action"),
            ),
        ) { args ->
            when (val action = args.optString("action").trim().lowercase()) {
                "add" -> {
                    val c = args.optString("content").trim()
                    if (c.isEmpty()) {
                        ToolRegistry.errorJson("content 不能为空")
                    } else {
                        val isProfile = args.optString("target").trim().lowercase() == "profile"
                        JSONObject().put("result", MemoryStore.add(context, c, isProfile)).toString()
                    }
                }
                "remove" -> {
                    val c = args.optString("content").trim()
                    JSONObject().put("result", MemoryStore.remove(context, c)).toString()
                }
                "list" -> MemoryStore.renderJson(context)
                "clear" -> {
                    MemoryStore.clear(context)
                    JSONObject().put("result", "记忆已清空").toString()
                }
                else -> ToolRegistry.errorJson("未知 action: $action（可用 add/remove/list/clear）")
            }
        }
    }
}

/** Task list tool for multi-step work, mirroring Hermes' `todo`. */
object TodoTools {
    private val items = mutableListOf<TodoItem>()

    data class TodoItem(val id: Int, val content: String, var status: String)

    fun register(context: Context) {
        ToolRegistry.register(
            name = "todo",
            toolset = "meta",
            description = "维护当前任务的待办清单。任务多于 3 步时先写清单，并在每一步完成后更新状态。",
            parameters = Schema.obj(
                properties = mapOf(
                    "items" to org.json.JSONObject().apply {
                        put("type", "array")
                        put("description", "待办项列表；传空数组表示读取当前清单")
                        put(
                            "items",
                            org.json.JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    org.json.JSONObject().apply {
                                        put("content", Schema.string("任务描述"))
                                        put(
                                            "status",
                                            Schema.string(
                                                "状态",
                                                enum = listOf("pending", "in_progress", "completed", "cancelled"),
                                            ),
                                        )
                                    },
                                )
                                put("required", org.json.JSONArray().put("content").put("status"))
                            },
                        )
                    },
                ),
            ),
        ) { args ->
            val arr = args.optJSONArray("items")
            if (arr == null || arr.length() == 0) {
                render()
            } else {
                items.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    items.add(
                        TodoItem(
                            id = i + 1,
                            content = o.optString("content"),
                            status = o.optString("status", "pending"),
                        ),
                    )
                }
                render()
            }
        }
    }

    private fun render(): String {
        if (items.isEmpty()) return JSONObject().put("items", org.json.JSONArray()).toString()
        val arr = org.json.JSONArray()
        items.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("content", t.content)
                    put("status", t.status)
                },
            )
        }
        return JSONObject().apply {
            put("count", items.size)
            put(
                "completed",
                items.count { it.status == "completed" || it.status == "cancelled" },
            )
            put("items", arr)
        }.toString()
    }
}
