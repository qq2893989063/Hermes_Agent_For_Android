package com.nousresearch.hermes.tools

import android.content.Context
import com.nousresearch.hermes.core.Schema
import com.nousresearch.hermes.core.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Web access tools: `web_search` + `web_fetch`.
 *
 * Search uses DuckDuckGo's keyless HTML endpoint (lite), so the app works with no
 * extra API key. `web_fetch` retrieves a URL and converts HTML to readable text —
 * this is the `read_file`-equivalent for the network on mobile.
 */
object WebTools {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun register(context: Context) {
        ToolRegistry.register(
            name = "web_search",
            toolset = "web",
            description = "联网搜索。返回标题、链接与摘要。当需要最新信息、" +
                "事实核查、或用户问到你不确定的内容时使用。",
            parameters = Schema.obj(
                properties = mapOf(
                    "query" to Schema.string("搜索关键词"),
                    "max_results" to Schema.integer("返回条数，默认 6，最多 15", 6),
                ),
                required = listOf("query"),
            ),
        ) { args ->
            val q = args.optString("query").trim()
            if (q.isEmpty()) return@register ToolRegistry.errorJson("query 不能为空")
            val n = args.optInt("max_results", 6).coerceIn(1, 15)
            withContext(Dispatchers.IO) { search(q, n) }
        }

        ToolRegistry.register(
            name = "web_fetch",
            toolset = "web",
            description = "抓取指定 URL 的网页并转换为纯文本。用于读取搜索结果里的具体页面、文档或文章正文。",
            parameters = Schema.obj(
                properties = mapOf(
                    "url" to Schema.string("要抓取的完整 URL，需以 http:// 或 https:// 开头"),
                    "max_chars" to Schema.integer("返回的最大字符数，默认 8000", 8000),
                ),
                required = listOf("url"),
            ),
        ) { args ->
            val url = args.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@register ToolRegistry.errorJson("url 必须以 http:// 或 https:// 开头")
            }
            val cap = args.optInt("max_chars", 8000).coerceIn(500, 40000)
            withContext(Dispatchers.IO) { fetch(url, cap) }
        }
    }

    private fun search(query: String, max: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"
        val body = runCatching { get(url) }.getOrElse {
            return ToolRegistry.errorJson("搜索请求失败: ${it.message}")
        }

        // The lite endpoint emits a flat table; anchors carry results, following rows
        // carry snippets. Parsing it keeps the tool dependency-free.
        val results = JSONArray()
        val anchor = Regex(
            "<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippet = Regex(
            "<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val links = anchor.findAll(body).toList()
        val snippets: List<String> = snippet.findAll(body).map { m ->
            cleanTags(m.groupValues[1]).trim()
        }.toList()

        links.take(max).forEachIndexed { i, m ->
            results.put(
                JSONObject().apply {
                    put("title", cleanTags(m.groupValues[2]).trim())
                    put("url", m.groupValues[1])
                    snippets.getOrNull(i)?.let { s -> put("snippet", s.take(400)) }
                },
            )
        }

        // Fallback for markup changes: return visible text so the model still gets data.
        if (results.length() == 0) {
            val plain = cleanTags(body).replace(Regex("\\s+"), " ").trim().take(3000)
            return JSONObject().apply {
                put("query", query)
                put("note", "未能解析出结构化结果，以下为页面纯文本，可能包含答案")
                put("text", plain)
            }.toString()
        }

        return JSONObject().apply {
            put("query", query)
            put("count", results.length())
            put("results", results)
        }.toString()
    }

    private fun fetch(url: String, cap: Int): String {
        val raw = runCatching { get(url) }.getOrElse {
            return ToolRegistry.errorJson("抓取失败: ${it.message}")
        }
        val text = if (raw.contains("<html", ignoreCase = true) || raw.contains("<body", ignoreCase = true)) {
            htmlToText(raw)
        } else {
            raw
        }
        val trimmed = text.trim().take(cap)
        return JSONObject().apply {
            put("url", url)
            put("chars", trimmed.length)
            put("truncated", text.length > cap)
            put("text", trimmed)
        }.toString()
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw RuntimeException("空响应")
            // Cap the read so a huge page cannot exhaust memory on a phone.
            val buf = ByteArray(1 shl 16)
            val sb = StringBuilder()
            var total = 0
            while (total < 3_000_000) {
                val r = stream.read(buf)
                if (r <= 0) break
                sb.append(String(buf, 0, r, Charsets.UTF_8))
                total += r
            }
            return sb.toString()
        }
    }

    /** Drops script/style/nav chrome and collapses whitespace into readable prose. */
    fun htmlToText(html: String): String {
        var s = html
        s = Regex(
            "<(script|style|noscript|svg|head)[^>]*>.*?</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).replace(s, " ")
        s = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(s, " ")
        s = Regex(
            "<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>",
            RegexOption.IGNORE_CASE,
        ).replace(s, "\n")
        s = Regex("<li[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n- ")
        s = cleanTags(s)
        s = decodeEntities(s)
        // Collapse runs of blank lines and long spaces.
        s = s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return s
    }

    /** Removes tags. Named distinctly so it never collides with a stdlib extension. */
    private fun cleanTags(s: String): String =
        Regex("<[^>]+>").replace(s, " ")

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&hellip;", "…")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
}
