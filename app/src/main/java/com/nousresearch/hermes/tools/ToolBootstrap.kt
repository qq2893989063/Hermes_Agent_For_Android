package com.nousresearch.hermes.tools

import android.content.Context

/**
 * Tool bootstrap. Registers every tool this APK exposes.
 *
 * This is the *trimmed* surface vs. upstream Hermes: messaging platforms, voice/wake
 * word, cron, browser/CDP, terminal backends, MCP, plugin marketplace and desktop-only
 * tools are all removed — they either need a server, a desktop, or permissions an
 * Android app cannot hold. What remains is the set that is genuinely useful on a phone.
 */
object ToolBootstrap {
    @Volatile
    private var done = false

    @Synchronized
    fun install(context: Context) {
        if (done) return
        WebTools.register(context)
        FileTools.register(context)
        SkillTools.register(context)
        MemoryTools.register(context)
        TodoTools.register(context)
        done = true
    }
}
