package com.nousresearch.hermes.core

import android.content.Context
import org.json.JSONArray

/**
 * Persistent memory — Hermes' cross-session notes, ported to device storage.
 *
 * Entries are injected into the system prompt each turn, so they must stay compact.
 * The store caps both the number and the rendered size of entries to protect the
 * context window, and de-duplicates case-insensitively.
 */
object MemoryStore {
    private const val PREFS = "hermes_memory"
    private const val KEY = "entries"
    private const val KEY_PROFILE = "profile"

    private const val MAX_ENTRIES = 60
    private const val MAX_RENDER_CHARS = 3500

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    fun profile(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_PROFILE, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /** Adds an entry, de-duplicating case-insensitively and enforcing the cap. */
    fun add(ctx: Context, text: String, asProfile: Boolean = false): String {
        val t = text.trim()
        if (t.isEmpty()) return "内容为空，未保存"
        val key = if (asProfile) KEY_PROFILE else KEY
        val current = if (asProfile) profile(ctx) else entries(ctx)
        if (current.any { it.equals(t, ignoreCase = true) }) {
            return "已存在相同内容，未重复保存"
        }
        val next = current + t
        // Drop oldest beyond the cap so memory cannot grow without bound.
        val capped = if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        save(ctx, key, capped)
        return "已保存" + if (capped.size != next.size) "（已按上限淘汰最旧条目）" else ""
    }

    fun remove(ctx: Context, text: String): String {
        val t = text.trim()
        var removed = false
        for (key in listOf(KEY, KEY_PROFILE)) {
            val cur = if (key == KEY) entries(ctx) else profile(ctx)
            val next = cur.filterNot { it.equals(t, ignoreCase = true) }
            if (next.size != cur.size) {
                save(ctx, key, next)
                removed = true
            }
        }
        return if (removed) "已删除" else "未找到匹配条目"
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).remove(KEY_PROFILE).apply()
    }

    private fun save(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    /** Rendered block for the system prompt; empty when nothing is stored. */
    fun render(ctx: Context): String {
        val e = entries(ctx)
        val p = profile(ctx)
        if (e.isEmpty() && p.isEmpty()) return ""
        val sb = StringBuilder("## 记忆（Memory）\n")
        sb.append("这些是跨会话保留的事实。除非用户要求，不要主动复述或炫耀它们。\n\n")
        if (p.isNotEmpty()) {
            sb.append("**用户画像**\n")
            p.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (e.isNotEmpty()) {
            sb.append("**笔记**\n")
            e.forEach { sb.append("- ").append(it).append('\n') }
        }
        val out = sb.toString()
        return if (out.length > MAX_RENDER_CHARS) {
            out.take(MAX_RENDER_CHARS) + "\n…（记忆过长已截断，可用 memory 工具清理）"
        } else {
            out
        }
    }

    fun renderJson(ctx: Context): String {
        val arr = JSONArray()
        entries(ctx).forEach { arr.put(it) }
        val parr = JSONArray()
        profile(ctx).forEach { parr.put(it) }
        return org.json.JSONObject()
            .put("profile", parr)
            .put("notes", arr)
            .toString()
    }
}
