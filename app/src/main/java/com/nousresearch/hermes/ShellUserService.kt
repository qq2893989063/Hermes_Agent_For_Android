package com.nousresearch.hermes

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Executes already-validated input commands in Shizuku's shell/root process. */
class ShellUserService : Service() {
    private val binder = object : IShellService.Stub() {
        override fun exec(command: String): String = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            ("$code ${out.trim()} ${err.trim()}").trim().take(2000)
        }.getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
