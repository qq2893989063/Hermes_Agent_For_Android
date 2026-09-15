package com.nousresearch.hermes.core

import android.content.ComponentName
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import rikka.shizuku.Shizuku
import com.nousresearch.hermes.IShellService
import com.nousresearch.hermes.ShellUserService

/** Owns Shizuku state so touch actions fail as text instead of crashing the chat. */
object ShizukuController {
    const val REQUEST_CODE = 9001
    private var context: Context? = null
    private var service: IShellService? = null
    private var connection: android.content.ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null

    fun init(context: Context) { this.context = context.applicationContext }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
    fun isPermissionGranted(): Boolean = runCatching { isAvailable() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun requestPermission() { runCatching { Shizuku.requestPermission(REQUEST_CODE) } }

    fun statusText(context: Context): String = runCatching {
        if (!isAvailable()) return@runCatching if (Shizuku.pingBinder()) "Shizuku 未运行" else "未安装 Shizuku"
        if (!isPermissionGranted()) return@runCatching "未授予权限"
        if (Shizuku.getUid() == 0) "已就绪（权限：Root）" else "已就绪（权限：Shell/ADB）"
    }.getOrElse { "未安装 Shizuku" }

    fun bind(): Boolean = runCatching {
        if (!isPermissionGranted() || service != null) return@runCatching false
        val c = context ?: return@runCatching false
        val a = Shizuku.UserServiceArgs(ComponentName(c, ShellUserService::class.java))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(1)
        args = a
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) { service = IShellService.Stub.asInterface(binder) }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        connection = conn
        Shizuku.bindUserService(a, conn)
        true
    }.getOrDefault(false)

    fun unbind() { runCatching { val a = args; val c = connection; if (a != null && c != null) Shizuku.unbindUserService(a, c, true) }; service = null; connection = null; args = null }

    fun tap(x: Int, y: Int): String = execValidated(listOf(x to y)) { "input tap $x $y" }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String = if (durationMs in 0..60000) execValidated(listOf(x1 to y1, x2 to y2)) { "input swipe $x1 $y1 $x2 $y2 $durationMs" } else "滑动时长无效"
    fun keyevent(keyCode: Int): String = if (keyCode in 0..300) exec("input keyevent $keyCode") else "按键代码无效"

    private fun execValidated(points: List<Pair<Int, Int>>, command: () -> String): String {
        val size = screenSize() ?: return "无法读取屏幕尺寸"
        if (points.any { it.first !in 0 until size.first || it.second !in 0 until size.second }) return "坐标超出屏幕范围"
        return exec(command())
    }
    private fun exec(command: String): String = runCatching { service?.exec(command) ?: "Shizuku 服务未连接" }.getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}" }

    fun screenSize(): Pair<Int, Int>? = runCatching {
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        if (Build.VERSION.SDK_INT >= 30) { val b = wm.currentWindowMetrics.bounds; b.width() to b.height() }
        else { val p = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getSize(p); p.x to p.y }
    }.getOrNull()
}
