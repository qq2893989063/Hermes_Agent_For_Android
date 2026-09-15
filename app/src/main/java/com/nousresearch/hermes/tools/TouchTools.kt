package com.nousresearch.hermes.tools

import android.content.Context
import com.nousresearch.hermes.core.Schema
import com.nousresearch.hermes.core.ShizukuController
import com.nousresearch.hermes.core.ToolRegistry
import org.json.JSONObject

object TouchTools {
    fun register(context: Context) {
        ToolRegistry.register(
            name = "touch_control", toolset = "device",
            description = "可通过 Shizuku 控制手机屏幕，执行点击、滑动和按键操作。需要用户先从右上角溢出菜单打开“触摸控制”并完成一次权限授权。",
            parameters = Schema.obj(mapOf(
                "action" to Schema.string("操作类型", listOf("tap", "swipe", "key")),
                "x" to Schema.integer("点击 X"), "y" to Schema.integer("点击 Y"),
                "x1" to Schema.integer("起点 X"), "y1" to Schema.integer("起点 Y"),
                "x2" to Schema.integer("终点 X"), "y2" to Schema.integer("终点 Y"),
                "duration_ms" to Schema.integer("滑动时长（毫秒）", 300),
                "keycode" to Schema.integer("Android 按键码")), listOf("action")),
            // Visibility keys off isUsable() (permission granted), NOT isReady() (binder live).
            // Gating on the binder hid this tool from the model until the user opened the
            // dialog manually, which made the model answer "没有可用的触控工具".
            checkFn = { ShizukuController.isUsable() },
        ) { args ->
            // Binding happens here, on demand: awaitReady() calls bind() and waits briefly.
            if (!ShizukuController.awaitReady()) return@register ToolRegistry.errorJson("Shizuku 服务尚未就绪。请用户从右上角溢出菜单打开“触摸控制”并授予权限；这是一次性的用户操作，完成后即可控制屏幕。")
            fun value(name: String): Int? = if (args.has(name) && !args.isNull(name)) args.optInt(name) else null
            val action = args.optString("action").lowercase()
            val out = runCatching {
                when (action) {
                    "tap" -> { val x=value("x") ?: return@runCatching ToolRegistry.errorJson("tap 需要 x 和 y"); val y=value("y") ?: return@runCatching ToolRegistry.errorJson("tap 需要 x 和 y"); JSONObject().put("action",action).put("x",x).put("y",y).put("result",ShizukuController.tap(x,y)) }
                    "swipe" -> { val x1=value("x1") ?: return@runCatching ToolRegistry.errorJson("swipe 需要 x1、y1、x2 和 y2"); val y1=value("y1") ?: return@runCatching ToolRegistry.errorJson("swipe 需要 x1、y1、x2 和 y2"); val x2=value("x2") ?: return@runCatching ToolRegistry.errorJson("swipe 需要 x1、y1、x2 和 y2"); val y2=value("y2") ?: return@runCatching ToolRegistry.errorJson("swipe 需要 x1、y1、x2 和 y2"); val d=args.optInt("duration_ms",300); JSONObject().put("action",action).put("x1",x1).put("y1",y1).put("x2",x2).put("y2",y2).put("duration_ms",d).put("result",ShizukuController.swipe(x1,y1,x2,y2,d)) }
                    "key" -> { val k=value("keycode") ?: return@runCatching ToolRegistry.errorJson("key 需要 keycode"); JSONObject().put("action",action).put("keycode",k).put("result",ShizukuController.keyevent(k)) }
                    else -> return@runCatching ToolRegistry.errorJson("action 必须是 tap、swipe 或 key")
                }.put("ok", true)
            }.getOrElse { ToolRegistry.errorJson("触摸操作失败：${it.message ?: it.javaClass.simpleName}") }
            out.toString()
        }
    }
}
