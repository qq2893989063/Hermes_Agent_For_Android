package com.nousresearch.hermes.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.nousresearch.hermes.core.Schema
import com.nousresearch.hermes.core.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * File + image reading tools, mirroring Hermes' `read_file` and vision path.
 *
 * On mobile there is no general file system access: the user grants URIs through the
 * system picker (SAF), and those are what these tools resolve. Images are re-encoded
 * to JPEG and returned as a data URL for the model's multimodal channel.
 */
object FileTools {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Max bytes we will inline as base64 (~4.5 MB after encoding). */
    private const val MAX_IMAGE_BYTES = 3_400_000
    private const val MAX_IMAGE_EDGE = 1568

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "csv", "tsv", "log", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs",
        "swift", "sh", "bash", "zsh", "ps1", "sql", "gradle", "properties", "env",
    )

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun register(context: Context) {
        ToolRegistry.register(
            name = "read_file",
            toolset = "files",
            description = "读取用户提供的文件内容（文本/代码/PDF/Office/表格）。" +
                "支持本地文件路径、content:// URI 或 http(s) 链接。",
            parameters = Schema.obj(
                properties = mapOf(
                    "path" to Schema.string("文件路径、content:// URI 或 http(s) 链接"),
                    "max_chars" to Schema.integer("返回最大字符数，默认 6000", 6000),
                ),
                required = listOf("path"),
            ),
        ) { args ->
            val p = args.optString("path").trim()
            if (p.isEmpty()) return@register ToolRegistry.errorJson("path 不能为空")
            val cap = args.optInt("max_chars", 6000).coerceIn(200, 60000)
            withContext(Dispatchers.IO) { readFile(context, p, cap) }
        }

        ToolRegistry.register(
            name = "read_image",
            toolset = "files",
            description = "读取并理解图片内容（本机图片路径、content:// URI 或 http(s) 图片链接）。" +
                "图片会交给具备视觉能力的模型分析。",
            parameters = Schema.obj(
                properties = mapOf(
                    "path" to Schema.string("图片路径、content:// URI 或 http(s) 链接"),
                    "question" to Schema.string("想从图片里了解什么，可留空"),
                ),
                required = listOf("path"),
            ),
        ) { args ->
            val p = args.optString("path").trim()
            if (p.isEmpty()) return@register ToolRegistry.errorJson("path 不能为空")
            val q = args.optString("question").trim()
            withContext(Dispatchers.IO) { readImage(context, p, q) }
        }

        ToolRegistry.register(
            name = "list_files",
            toolset = "files",
            description = "列出某个目录下的文件，用于了解用户提供的文件夹内容。",
            parameters = Schema.obj(
                properties = mapOf(
                    "dir" to Schema.string("目录的绝对路径，例如 /sdcard/Download"),
                ),
                required = listOf("dir"),
            ),
        ) { args ->
            val d = args.optString("dir").trim()
            if (d.isEmpty()) return@register ToolRegistry.errorJson("dir 不能为空")
            withContext(Dispatchers.IO) { listDir(d) }
        }
    }

    // ---- reading -----------------------------------------------------------

    private fun readFile(context: Context, path: String, cap: Int): String {
        // Remote URL
        if (path.startsWith("http://") || path.startsWith("https://")) {
            val bytes = download(path) ?: return ToolRegistry.errorJson("下载失败: $path")
            return decodeAndDescribe(path, bytes, cap)
        }

        val bytes: ByteArray = if (path.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
            }.getOrNull() ?: return ToolRegistry.errorJson("无法通过该 URI 读取文件（可能缺少权限）")
        } else {
            val f = File(path)
            if (!f.exists()) return ToolRegistry.errorJson("文件不存在: $path")
            if (f.isDirectory) return listDir(path)
            if (!f.canRead()) return ToolRegistry.errorJson("没有读取权限: $path")
            runCatching { f.readBytes() }.getOrElse {
                return ToolRegistry.errorJson("读取失败: ${it.message}")
            }
        }
        return decodeAndDescribe(path, bytes, cap)
    }

    /** Dispatch on extension: binary documents get a clear "not supported here" note. */
    private fun decodeAndDescribe(path: String, bytes: ByteArray, cap: Int): String {
        val ext = path.substringAfterLast('.', "").lowercase().substringBefore('?')
        val isBinaryDoc = ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods")

        if (ext in IMAGE_EXTS) {
            return JSONObject().apply {
                put("path", path)
                put("kind", "image")
                put("bytes", bytes.size)
                put("note", "这是图片。请改用 read_image 工具来查看其内容。")
            }.toString()
        }

        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        // Heuristic: too many NULs/replacement chars means it is not really text.
        val looksText = decoded != null &&
            decoded.count { it == '\u0000' } == 0 &&
            decoded.count { it == '\uFFFD' } < decoded.length / 50

        if (!looksText) {
            return JSONObject().apply {
                put("path", path)
                put("bytes", bytes.size)
                put("kind", if (isBinaryDoc) "document" else "binary")
                put(
                    "note",
                    if (isBinaryDoc) {
                        "这是二进制文档格式（$ext）。手机端未内置该格式的解析器，" +
                            "请改用 read_image（适用于扫描件截图）或让用户在电脑端转换后再提供文本版本。"
                    } else {
                        "该文件不是纯文本，无法直接读取。"
                    },
                )
            }.toString()
        }

        val text: String = decoded!!
        val truncated = text.length > cap
        return JSONObject().apply {
            put("path", path)
            put("kind", "text")
            put("ext", ext)
            put("bytes", bytes.size)
            put("chars", text.length)
            put("truncated", truncated)
            put("content", text.take(cap))
        }.toString()
    }

    // ---- images -----------------------------------------------------------

    private fun readImage(context: Context, path: String, question: String): String {
        val raw: ByteArray = if (path.startsWith("http://") || path.startsWith("https://")) {
            download(path) ?: return ToolRegistry.errorJson("图片下载失败")
        } else if (path.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
            }.getOrNull() ?: return ToolRegistry.errorJson("无法读取该 URI 指向的图片")
        } else {
            val f = File(path)
            if (!f.exists()) return ToolRegistry.errorJson("图片不存在: $path")
            runCatching { f.readBytes() }.getOrElse {
                return ToolRegistry.errorJson("读取失败: ${it.message}")
            }
        }

        if (raw.isEmpty()) return ToolRegistry.errorJson("图片内容为空")

        // Decode + downscale. Providers reject oversized payloads, and phones produce
        // 10MP+ captures that would blow past every limit if sent verbatim.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ToolRegistry.errorJson("无法解析该图片（格式不支持或文件损坏）")
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSample(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return ToolRegistry.errorJson("图片解码失败")

        val scaled = scaleToFit(bmp, MAX_IMAGE_EDGE)
        var quality = 85
        var jpeg = compress(scaled, quality)
        // Step quality down until the payload fits the inline budget.
        while (jpeg.size > MAX_IMAGE_BYTES && quality > 35) {
            quality -= 15
            jpeg = compress(scaled, quality)
        }
        if (scaled !== bmp) bmp.recycle()

        if (jpeg.size > MAX_IMAGE_BYTES) {
            return ToolRegistry.errorJson(
                "图片过大（${jpeg.size / 1024}KB），即使压缩后仍超出限制。请让用户先裁剪或缩小图片。",
            )
        }

        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val w = scaled.width
        val h = scaled.height

        return JSONObject().apply {
            put("path", path)
            put("kind", "image")
            put("width", w)
            put("height", h)
            put("approx_bytes", jpeg.size)
            put("data_url", dataUrl)
            if (question.isNotEmpty()) put("question", question)
            put(
                "instruction",
                "图片已作为 data URL 附在 data_url 字段。请直接基于该图片回答问题" +
                    (if (question.isNotEmpty()) "：「$question」" else "。"),
            )
        }.toString()
    }

    private fun computeSample(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var maxEdge = maxOf(w, h)
        while (maxEdge / 2 >= target) {
            maxEdge /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToFit(bmp: Bitmap, target: Int): Bitmap {
        val maxEdge = maxOf(bmp.width, bmp.height)
        if (maxEdge <= target) return bmp
        val ratio = target.toFloat() / maxEdge
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun compress(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    // ---- listing ----------------------------------------------------------

    private fun listDir(dir: String): String {
        val f = File(dir)
        if (!f.exists()) return ToolRegistry.errorJson("目录不存在: $dir")
        if (!f.isDirectory) return ToolRegistry.errorJson("不是目录: $dir")
        val entries = runCatching { f.listFiles() }.getOrNull()
            ?: return ToolRegistry.errorJson("无法列出目录（权限不足）: $dir")
        val arr = org.json.JSONArray()
        entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .take(400)
            .forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("dir", e.isDirectory)
                        if (!e.isDirectory) put("bytes", e.length())
                    },
                )
            }
        return JSONObject().apply {
            put("dir", dir)
            put("count", entries.size)
            put("entries", arr)
        }.toString()
    }

    private fun download(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.bytes()
        }
    }.getOrNull()

    /** Resolves a display name for a content URI, for nicer tool output. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return@runCatching c.getString(idx)
            }
        }
        URLDecoder.decode(uri.lastPathSegment ?: "", "UTF-8")
    }.getOrNull()
}
