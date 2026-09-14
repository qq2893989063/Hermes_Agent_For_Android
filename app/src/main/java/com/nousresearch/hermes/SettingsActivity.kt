package com.nousresearch.hermes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nousresearch.hermes.core.ModelConfig
import com.nousresearch.hermes.core.FontConfig
import com.nousresearch.hermes.core.FontFamily
import com.nousresearch.hermes.core.FontScale
import com.nousresearch.hermes.core.SkillRegistry
import com.nousresearch.hermes.core.ToolRegistry
import com.nousresearch.hermes.databinding.ActivitySettingsBinding
import com.nousresearch.hermes.ui.Insets
import kotlinx.coroutines.launch

/**
 * Model configuration + a live inventory of what the agent can actually do.
 *
 * The tool/skill lists are rendered from the real registry, not hard-coded, so the
 * screen doubles as a truthful capability report for the shipped APK.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enableEdgeToEdge(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // This Activity uses the framework ActionBar (Theme.Hermes.Settings). Under
        // targetSdk 35 edge-to-edge the decor no longer clears the status bar, so pad
        // the content root's top by the cutout/status inset ourselves.
        Insets.padVertical(b.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        loadIntoForm()
        renderCapabilities()
        renderEffectiveUrl()

        // Keep the resolved-endpoint preview in sync as the user types.
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = renderEffectiveUrl()
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, bc: Int, c: Int) = Unit
        }
        b.etBaseUrl.addTextChangedListener(watcher)
        b.etCustomUrl.addTextChangedListener(watcher)

        b.btnSave.setOnClickListener { save() }

        b.btnTest.setOnClickListener {
            val cfg = readForm()
            val problem = validate(cfg)
            if (problem != null) {
                b.tvTestResult.text = problem
                return@setOnClickListener
            }
            ModelConfig.save(this, cfg)
            b.tvTestResult.text = "测试中…"
            lifecycleScope.launch {
                val agent = com.nousresearch.hermes.core.Agent(this@SettingsActivity, cfg)
                b.tvTestResult.text = agent.testConnection()
            }
        }

        b.btnFetchModels.setOnClickListener {
            val cfg = readForm()
            if (cfg.apiKey.isBlank()) {
                b.tvTestResult.text = "请先填写 API Key"
                return@setOnClickListener
            }
            val problem = validate(cfg)
            if (problem != null) {
                b.tvTestResult.text = problem
                return@setOnClickListener
            }
            ModelConfig.save(this, cfg)
            b.tvTestResult.text = "拉取中…"
            lifecycleScope.launch {
                val agent = com.nousresearch.hermes.core.Agent(this@SettingsActivity, cfg)
                val models = runCatching { agent.availableModels() }.getOrNull()
                // Flow emits once; collect the first value.
                var list: List<String> = emptyList()
                models?.collect { list = it }
                if (list.isEmpty()) {
                    b.tvTestResult.text = "未获取到模型列表（可手动填写模型名）"
                } else {
                    b.tvTestResult.text = "共 ${list.size} 个模型"
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("选择模型")
                        .setItems(list.toTypedArray()) { _, which ->
                            b.etModel.setText(list[which])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadIntoForm() {
        val c = ModelConfig.load(this)
        b.etBaseUrl.setText(c.baseUrl)
        b.etCustomUrl.setText(c.customChatUrl)
        b.etApiKey.setText(c.apiKey)
        b.etModel.setText(c.model)
        b.etTemperature.setText(c.temperature.toString())
        b.etMaxTokens.setText(c.maxTokens.toString())
        b.etSystemPrompt.setText(c.systemPrompt)
        val font = FontConfig.load(this)
        b.spFontFamily.setSelection(FontFamily.values().indexOf(font.family))
        b.spFontScale.setSelection(FontScale.values().indexOf(font.scale))
    }

    private fun readForm(): ModelConfig = ModelConfig(
        baseUrl = b.etBaseUrl.text.toString().trim().ifBlank { ModelConfig.DEFAULT_BASE_URL },
        apiKey = b.etApiKey.text.toString().trim(),
        model = b.etModel.text.toString().trim().ifBlank { ModelConfig.DEFAULT_MODEL },
        temperature = b.etTemperature.text.toString().trim().toDoubleOrNull() ?: 0.7,
        maxTokens = b.etMaxTokens.text.toString().trim().toIntOrNull() ?: 4096,
        systemPrompt = b.etSystemPrompt.text.toString().trim(),
        customChatUrl = b.etCustomUrl.text.toString().trim(),
    )

    /** Returns an error string when the form cannot be used, else null. */
    private fun validate(cfg: ModelConfig): String? =
        ModelConfig.validateChatUrl(cfg.customChatUrl)

    /** Shows exactly which URL will be called, so relay paths are never a guess. */
    private fun renderEffectiveUrl() {
        val cfg = readForm()
        val problem = ModelConfig.validateChatUrl(cfg.customChatUrl)
        if (problem != null) {
            b.tvEffectiveUrl.text = "✗ 自定义地址无效：$problem"
            b.tvEffectiveUrl.setTextColor(getColor(R.color.hermes_error))
            return
        }
        b.tvEffectiveUrl.setTextColor(getColor(R.color.hermes_ok))
        b.tvEffectiveUrl.text = if (cfg.customChatUrl.isBlank()) {
            val candidates = cfg.chatUrlCandidates()
            buildString {
                append(getString(R.string.effective_url_candidates_header))
                append('\n')
                append(candidates.mapIndexed { index, url ->
                    getString(R.string.effective_url_candidate, index + 1, url)
                }.joinToString("\n"))
                append('\n')
                append(getString(R.string.effective_url_fallback_note))
            }
        } else {
            buildString {
                append(getString(R.string.effective_url_custom_header))
                append('\n')
                append(getString(R.string.effective_url_candidate, 1, cfg.chatUrlCandidates().single()))
            }
        }
    }

    private fun save() {
        val cfg = readForm()
        val problem = validate(cfg)
        if (problem != null) {
            b.tvTestResult.text = problem
            renderEffectiveUrl()
            return
        }
        ModelConfig.save(this, cfg)
        FontConfig.save(this, FontFamily.values()[b.spFontFamily.selectedItemPosition], FontScale.values()[b.spFontScale.selectedItemPosition])
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        renderCapabilities()
        renderEffectiveUrl()
    }

    private fun renderCapabilities() {
        val byToolset = ToolRegistry.byToolset()
        b.tvTools.text = if (byToolset.isEmpty()) {
            "（无）"
        } else {
            byToolset.entries.joinToString("\n") { (ts, names) ->
                "• $ts: ${names.joinToString(", ")}"
            }
        }

        val skills = SkillRegistry.all(this)
        b.tvSkills.text = if (skills.isEmpty()) {
            "（无）"
        } else {
            skills.groupBy { it.category }.entries.joinToString("\n") { (cat, items) ->
                "• $cat: ${items.joinToString(", ") { it.name }}"
            }
        }

        val cfg = ModelConfig.load(this)
        val agent = com.nousresearch.hermes.core.Agent(this, cfg)
        val bytes = agent.currentSystemPrompt().toByteArray(Charsets.UTF_8).size
        b.tvPromptSize.text = "系统提示词约 $bytes 字节 · 可用工具 ${ToolRegistry.available().size} 个 · 技能 ${skills.size} 个"
    }
}
