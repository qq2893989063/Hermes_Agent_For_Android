package com.nousresearch.hermes.core

import android.content.ComponentName
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.nousresearch.hermes.IShellService
import com.nousresearch.hermes.ShellUserService
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

object ShizukuController {
    const val REQUEST_CODE = 9001

    /**
     * Tag for adb-visible diagnostics.
     *
     * The bind path was completely silent: every failure was swallowed by runCatching and the
     * ServiceConnection callbacks never logged, so "已授权但服务未连接" gave no way to tell a
     * version mismatch from a rejected bind from an exception. Log every branch so
     * `adb logcat -s HermesShizuku` shows the real reason.
     */
    private const val TAG = "HermesShizuku"

    private fun log(msg: String) {
        runCatching { android.util.Log.i(TAG, msg) }
    }

    private fun logErr(msg: String, t: Throwable? = null) {
        runCatching { android.util.Log.e(TAG, msg, t) }
    }

    /** Last bind failure, surfaced in the dialog so it is visible without adb. */
    @Volatile
    var lastError: String? = null
        private set

    private var context: Context? = null
    private var service: IShellService? = null
    private var connection: android.content.ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    fun init(context: Context) { this.context = context.applicationContext }
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
    fun isPermissionGranted(): Boolean = runCatching { isAvailable() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun isReady(): Boolean = runCatching { isPermissionGranted() && service != null }.getOrDefault(false)

    /**
     * "The user has done everything only a human can do" -- Shizuku is running and our
     * permission is granted. Deliberately does NOT require a bound user service.
     *
     * Tool visibility must key off this, not [isReady]. The binder only exists after
     * [bind], and binding happens on demand inside the handler; gating visibility on the
     * binder hid `touch_control` from the model until the user manually opened a dialog,
     * so the model answered "没有可用的触控工具" forever (chicken-and-egg).
     */
    fun isUsable(): Boolean = runCatching { isAvailable() && isPermissionGranted() }.getOrDefault(false)
    fun requestPermission() { runCatching { log("requestPermission($REQUEST_CODE)"); Shizuku.requestPermission(REQUEST_CODE) }.onFailure { logErr("requestPermission threw", it) } }
    fun statusText(context: Context): String = runCatching {
        val installed = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
        if (!installed) return@runCatching "未安装 Shizuku"
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return@runCatching "Shizuku 未运行"
        if (!isPermissionGranted()) return@runCatching "未授予权限"
        if (service != null) "已连接" else {
            val e = lastError
            if (e != null) "已授权但服务未连接：$e" else "已授权但服务未连接"
        }
    }.getOrElse { "未安装 Shizuku" }
    fun bind(): Boolean = runCatching {
        if (!isPermissionGranted()) { log("bind: permission not granted"); return@runCatching false }
        if (service != null) { log("bind: already connected"); return@runCatching true }
        val c = context ?: run { logErr("bind: no context"); return@runCatching false }
        val component = ComponentName(c, ShellUserService::class.java)
        val a = Shizuku.UserServiceArgs(component).daemon(false).processNameSuffix("shell").debuggable(false).version(1)
        args = a
        // Report the environment once: a version/uid mismatch is the usual cause of a bind
        // that never calls back.
        log(
            "bind: requesting user service component=$component " +
                "shizukuVersion=${runCatching { Shizuku.getVersion() }.getOrDefault(-1)} " +
                "uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)} " +
                "isPreV11=${runCatching { Shizuku.isPreV11() }.getOrDefault(true)}",
        )
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                log("onServiceConnected: name=$name binder=${if (binder == null) "null" else "ok"}")
                if (binder == null) {
                    lastError = "onServiceConnected 收到空 binder"
                    logErr("onServiceConnected got a null binder")
                    return
                }
                service = IShellService.Stub.asInterface(binder)
                lastError = null
                log("onServiceConnected: service bound")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                lastError = "服务已断开"
                logErr("onServiceDisconnected: name=$name")
            }
        }
        connection = conn
        runCatching { Shizuku.bindUserService(a, conn) }
            .onFailure {
                lastError = it.message ?: it.javaClass.simpleName
                logErr("bindUserService threw", it)
            }
            .onSuccess { log("bindUserService: dispatched, waiting for callback") }
        service != null
    }.getOrElse {
        lastError = it.message ?: it.javaClass.simpleName
        logErr("bind threw", it)
        false
    }

    /**
     * Why a "granted" permission can still fail to bind.
     *
     * Shizuku keeps its own list of authorised apps. If that list does not actually contain
     * this app, `checkSelfPermission()` can still report granted from cached state while
     * Shizuku silently drops `bindUserService` -- no callback, no exception, no log. That
     * exact mismatch was observed on device: the app showed 已授权 but Shizuku's own screen
     * said 已授权 0 个应用, and the bind never called back.
     *
     * This returns a message the user can act on, or null when the state is consistent.
     */
    fun permissionMismatchHint(): String? = runCatching {
        if (!isPermissionGranted()) return@runCatching null
        if (service != null) return@runCatching null
        val e = lastError
        if (e != null) {
            "绑定服务失败（$e）。请在 Shizuku 应用中确认本应用已在「已授权的应用」列表中；" +
                "若列表显示 0 个应用，请重新授予权限，必要时重启 Shizuku 服务。"
        } else {
            null
        }
    }.getOrNull()
    suspend fun awaitReady(timeoutMs: Long = 3000): Boolean = runCatching {
        if (isReady()) return@runCatching true
        bind()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        while (!isReady() && System.currentTimeMillis() < deadline) delay(50)
        val ok = isReady()
        if (!ok) {
            val why = lastError ?: "绑定超时（${timeoutMs}ms 内未回调 onServiceConnected）"
            lastError = why
            logErr("awaitReady: FAILED after ${timeoutMs}ms -- $why")
        } else {
            log("awaitReady: ready")
        }
        ok
    }.getOrElse { lastError = it.message; logErr("awaitReady threw", it); false }
    fun unbind() { runCatching { val a = args; val c = connection; if (a != null && c != null) Shizuku.unbindUserService(a, c, true) }; service = null; connection = null; args = null }
    fun tap(x: Int, y: Int): String = execValidated(listOf(x to y)) { "input tap $x $y" }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String = if (durationMs in 0..60000) execValidated(listOf(x1 to y1, x2 to y2)) { "input swipe $x1 $y1 $x2 $y2 $durationMs" } else "滑动时长无效"
    fun keyevent(keyCode: Int): String = if (keyCode in 0..300) exec("input keyevent $keyCode") else "按键代码无效"
    private fun execValidated(points: List<Pair<Int, Int>>, command: () -> String): String {
        val size = screenSize() ?: return "无法读取屏幕尺寸"
        if (points.any { it.first !in 0 until size.first || it.second !in 0 until size.second }) return "坐标超出屏幕范围"
        return exec(command())
    }
    private fun exec(command: String): String = runCatching {
        val svc = service
        if (svc == null) {
            lastError = "服务未连接"
            logErr("exec: no service for '$command'")
            return@runCatching "Shizuku 服务未连接"
        }
        log("exec: $command")
        val out = svc.exec(command)
        log("exec result: $out")
        out
    }.getOrElse {
        lastError = it.message ?: it.javaClass.simpleName
        logErr("exec threw for '$command'", it)
        "执行失败：${it.message ?: it.javaClass.simpleName}"
    }
    fun screenSize(): Pair<Int, Int>? = runCatching {
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        if (Build.VERSION.SDK_INT >= 30) { val b = wm.currentWindowMetrics.bounds; b.width() to b.height() } else { val p = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getSize(p); p.x to p.y }
    }.getOrNull()
}
