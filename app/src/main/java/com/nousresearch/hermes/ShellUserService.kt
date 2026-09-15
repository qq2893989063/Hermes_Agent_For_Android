package com.nousresearch.hermes

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * Runs as a Shizuku user service -- i.e. as the shell (uid 2000) or root process -- and
 * executes already-validated input commands there.
 *
 * IMPORTANT: this class must NOT extend android.app.Service.
 *
 * Shizuku does not bind this through the Android service mechanism. `shizuku_server`
 * INSTANTIATES the class itself by reflection and hands it a Context, so a real
 * android.app.Service subclass can never be constructed that way: the bind is dropped
 * silently -- no callback, no exception, and no entry in logcat on either side. That was the
 * cause of the permanent "已授权但服务未连接" state. The official demo's UserService derives
 * straight from the generated Stub for exactly this reason.
 *
 * A no-argument constructor is required; the Context constructor is available from
 * Shizuku API v13 and must be annotated @Keep so R8 does not strip it.
 */
class ShellUserService : IShellService.Stub {

    /**
     * Constructor is required.
     */
    constructor() : super() {
        Log.i(TAG, "constructor")
    }

    /**
     * Context constructor, available from Shizuku API v13.
     */
    @Keep
    constructor(context: Context) : super() {
        Log.i(TAG, "constructor with Context: $context")
    }

    override fun exec(command: String): String = runCatching {
        Log.i(TAG, "exec: $command")
        // Run through sh so the command string behaves like an `adb shell` invocation.
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching "执行超时（${EXEC_TIMEOUT_SECONDS}s）：$command"
        }
        val code = process.exitValue()
        Log.i(TAG, "exec exit=$code out=${output.take(200)}")
        output.trim().ifEmpty { "exit=$code" }
    }.getOrElse { e ->
        Log.e(TAG, "exec failed: $command", e)
        "执行失败：${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Reserved: Shizuku calls destroy() to dispose of the user service.
     */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    companion object {
        private const val TAG = "HermesShellService"
        private const val EXEC_TIMEOUT_SECONDS = 15L
    }
}
