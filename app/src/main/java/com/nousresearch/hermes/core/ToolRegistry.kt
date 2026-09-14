package com.nousresearch.hermes.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One registered tool.
 *
 * Ported from Hermes' `ToolEntry`: a tool is a (name, toolset, JSON-Schema, handler)
 * tuple. `checkFn` mirrors Hermes' availability probe — a tool whose prerequisites
 * are unmet is not exposed to the model.
 */
class ToolEntry(
    val name: String,
    val toolset: String,
    val description: String,
    val parameters: JSONObject,
    val checkFn: () -> Boolean = { true },
    val handler: suspend (JSONObject) -> String,
) {
    /** OpenAI function-tool schema advertised to the provider. */
    fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            },
        )
    }
}

/**
 * Singleton registry collecting tool schemas + handlers.
 *
 * Mirrors Hermes' `ToolRegistry`: tools self-register with `register(name, toolset,
 * schema, handler)`, and `definitions()` returns only the entries whose `checkFn`
 * passes — so an unavailable backend silently drops out of the prompt instead of
 * failing mid-turn.
 */
object ToolRegistry {
    private val tools = LinkedHashMap<String, ToolEntry>()

    fun register(entry: ToolEntry) {
        require(entry.name.isNotBlank()) { "tool name must not be blank" }
        tools[entry.name] = entry
    }

    fun register(
        name: String,
        toolset: String,
        description: String,
        parameters: JSONObject,
        checkFn: () -> Boolean = { true },
        handler: suspend (JSONObject) -> String,
    ) = register(ToolEntry(name, toolset, description, parameters, checkFn, handler))

    fun get(name: String): ToolEntry? = tools[name]

    fun all(): List<ToolEntry> = tools.values.toList()

    /** Entries whose availability probe currently passes. */
    fun available(): List<ToolEntry> = tools.values.filter {
        runCatching { it.checkFn() }.getOrDefault(false)
    }

    /** Toolset -> tool names, for the `skills`/tool listing surfaced in the UI. */
    fun byToolset(): Map<String, List<String>> =
        available().groupBy { it.toolset }.mapValues { (_, v) -> v.map { it.name }.sorted() }

    /** OpenAI `tools` array for the request body. */
    fun definitions(): JSONArray = JSONArray().apply {
        available().forEach { put(it.schema()) }
    }

    /** Dispatch a model tool call to its handler; never throws. */
    suspend fun dispatch(call: ToolCall): String {
        val entry = tools[call.name]
            ?: return errorJson("Unknown tool: ${call.name}")
        if (runCatching { entry.checkFn() }.getOrDefault(false).not()) {
            return errorJson("Tool '${call.name}' is not available on this device")
        }
        return runCatching { entry.handler(call.args()) }
            .getOrElse { errorJson("${it.javaClass.simpleName}: ${it.message}") }
    }

    fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    fun reset() = tools.clear()
}

/** Build a JSON Schema object node. Helper for concise tool declarations. */
object Schema {
    fun obj(
        properties: Map<String, JSONObject>,
        required: List<String> = emptyList(),
        description: String? = null,
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        if (description != null) put("description", description)
        put(
            "properties",
            JSONObject().apply { properties.forEach { (k, v) -> put(k, v) } },
        )
        put("required", JSONArray().apply { required.forEach { put(it) } })
        put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JSONArray().apply { enum.forEach { put(it) } })
        }

    fun integer(description: String, default: Int? = null): JSONObject =
        JSONObject().apply {
            put("type", "integer")
            put("description", description)
            if (default != null) put("default", default)
        }

    fun bool(description: String, default: Boolean? = null): JSONObject =
        JSONObject().apply {
            put("type", "boolean")
            put("description", description)
            if (default != null) put("default", default)
        }

    fun strings(description: String): JSONObject =
        JSONObject().apply {
            put("type", "array")
            put("description", description)
            put("items", JSONObject().put("type", "string"))
        }
}
