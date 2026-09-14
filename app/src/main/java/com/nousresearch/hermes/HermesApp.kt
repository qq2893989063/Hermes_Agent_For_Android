package com.nousresearch.hermes

import android.app.Application
import com.nousresearch.hermes.core.SkillRegistry
import com.nousresearch.hermes.tools.ToolBootstrap

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Index bundled skills and register tools once, off the UI critical path
        // (both are cheap, but doing it here keeps the first message latency flat).
        ToolBootstrap.install(this)
        runCatching { SkillRegistry.ensureIndexed(this) }
    }

    companion object {
        lateinit var instance: HermesApp
            private set
    }
}
