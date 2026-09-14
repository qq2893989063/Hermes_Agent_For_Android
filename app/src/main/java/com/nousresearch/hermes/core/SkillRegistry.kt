package com.nousresearch.hermes.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A skill loaded from the bundled `assets/skills/**/SKILL.md` tree. */
data class Skill(
    val name: String,
    val description: String,
    val category: String,
    val body: String,
    val path: String,
) {
    /** Full markdown including YAML frontmatter, as the model should see it. */
    fun render(): String = buildString {
        append("<!-- skill: $path -->\n")
        append(body)
    }
}

/**
 * Skill system — Hermes' procedural memory, ported.
 *
 * Skills ship inside the APK under `assets/skills/<category>/<name>/SKILL.md` and are
 * indexed once at startup. Two access paths, matching the original:
 *   - [indexForPrompt] injects a compact name+description index into the system prompt,
 *     so the model knows what's available without paying for every full body;
 *   - [load] returns a complete SKILL.md when the model calls `skill_view`.
 *
 * The frontmatter parser is deliberately forgiving: a malformed header must not take
 * the whole index down, so such files fall back to their directory name.
 */
object SkillRegistry {
    private var skills: List<Skill> = emptyList()
    private var indexed = false

    @Synchronized
    fun ensureIndexed(context: Context) {
        if (indexed) return
        skills = runCatching { scan(context) }.getOrDefault(emptyList())
        indexed = true
    }

    fun all(context: Context): List<Skill> {
        ensureIndexed(context)
        return skills
    }

    fun get(context: Context, name: String): Skill? {
        ensureIndexed(context)
        val key = name.trim().lowercase()
        return skills.firstOrNull { it.name.lowercase() == key }
            ?: skills.firstOrNull { it.path.lowercase().endsWith("/$key/SKILL.md") }
    }

    /** All skills in a category, or every skill when [category] is blank. */
    fun byCategory(context: Context, category: String?): List<Skill> {
        ensureIndexed(context)
        if (category.isNullOrBlank()) return skills
        val c = category.trim().lowercase()
        return skills.filter { it.category.lowercase() == c }
    }

    fun categories(context: Context): List<String> {
        ensureIndexed(context)
        return skills.map { it.category }.distinct().sorted()
    }

    /**
     * Compact index injected into the system prompt. Only names + descriptions, so a
     * large skill library does not consume the context window.
     */
    fun indexForPrompt(context: Context): String {
        val list = all(context)
        if (list.isEmpty()) return ""
        return buildString {
            append("## 可用技能（Skills）\n")
            append("下面是可用的技能索引。当某项任务与某个技能相关时，")
            append("先用 `skill_view` 读取该技能的完整内容，再严格按其中的步骤执行。\n\n")
            list.groupBy { it.category }.toSortedMap().forEach { (cat, items) ->
                append("**$cat**\n")
                items.sortedBy { it.name }.forEach { s ->
                    append("- `${s.name}` — ${s.description}\n")
                }
            }
        }.trimEnd()
    }

    /** Skill summaries for the `skills_list` tool. */
    fun listJson(context: Context, category: String?): String {
        val items = byCategory(context, category)
        if (items.isEmpty()) {
            return JSONObject().put("skills", org.json.JSONArray())
                .put("note", "没有匹配的技能").toString()
        }
        val arr = org.json.JSONArray()
        items.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("description", s.description)
                    put("category", s.category)
                },
            )
        }
        return JSONObject().apply {
            put("count", items.size)
            put("skills", arr)
        }.toString()
    }

    // ---- loading -----------------------------------------------------------

    private fun scan(context: Context): List<Skill> {
        val assets = context.assets
        val found = mutableListOf<Skill>()
        // Walk assets/skills/**/SKILL.md. AssetManager.list() is per-directory, so
        // recurse explicitly; depth is bounded in practice (category/name).
        fun walk(path: String, depth: Int) {
            if (depth > 4) return
            val children = runCatching { assets.list(path) }.getOrNull() ?: return
            if (children.isEmpty()) {
                // Leaf: a file at this path.
                if (path.endsWith("SKILL.md")) readSkill(context, path)?.let { found.add(it) }
                return
            }
            for (child in children) {
                val full = if (path.isEmpty()) child else "$path/$child"
                if (child.endsWith(".md")) {
                    if (child == "SKILL.md") readSkill(context, full)?.let { found.add(it) }
                } else {
                    walk(full, depth + 1)
                }
            }
        }
        walk("skills", 0)
        return found.distinctBy { it.name }
    }

    private fun readSkill(context: Context, assetPath: String): Skill? = runCatching {
        val raw = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val (meta, body) = parseFrontmatter(raw)
        // assets/skills/<category>/<name>/SKILL.md
        val segs = assetPath.split('/')
        val name = meta["name"]?.takeIf { it.isNotBlank() }
            ?: segs.getOrNull(segs.size - 2)
            ?: return null
        val category = segs.getOrNull(1) ?: "other"
        val description = meta["description"]?.trim()?.trim('"', '\'')
            ?: body.lineSequence().firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()
            ?: ""
        Skill(
            name = name,
            description = description,
            category = category,
            body = raw,
            path = assetPath,
        )
    }.getOrNull()

    /** Splits YAML frontmatter from the body; tolerant of absent/malformed headers. */
    private fun parseFrontmatter(raw: String): Pair<Map<String, String>, String> {
        val text = raw.trimStart('\uFEFF', '\n', '\r', ' ')
        if (!text.startsWith("---")) return emptyMap<String, String>() to raw
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to raw
        val header = text.substring(3, end)
        val body = text.substring(end + 4)
        val meta = LinkedHashMap<String, String>()
        header.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty()) meta[k] = v
            }
        }
        return meta to body
    }

    /** Test/debug helper: force a rescan. */
    @Synchronized
    fun invalidate() {
        indexed = false
        skills = emptyList()
    }

    /** Skills whose body file exists on disk — used by import/export. */
    fun isBundledAsset(skill: Skill): Boolean = skill.path.startsWith("skills/")

    fun fileFor(context: Context, skill: Skill): File =
        File(context.filesDir, skill.path)
}
