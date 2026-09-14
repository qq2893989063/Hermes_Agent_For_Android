package com.nousresearch.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.nousresearch.hermes.core.Agent
import com.nousresearch.hermes.core.AgentEvent
import com.nousresearch.hermes.core.MemoryStore
import com.nousresearch.hermes.core.ModelConfig
import com.nousresearch.hermes.core.ChatMessage
import com.nousresearch.hermes.core.Session
import com.nousresearch.hermes.core.SessionStore
import com.nousresearch.hermes.core.SkillRegistry
import com.nousresearch.hermes.core.ToolRegistry
import com.nousresearch.hermes.databinding.ActivityMainBinding
import com.nousresearch.hermes.tools.FileTools
import com.nousresearch.hermes.ui.Insets
import com.nousresearch.hermes.ui.MessageAdapter
import com.nousresearch.hermes.ui.SessionAdapter
import com.nousresearch.hermes.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Chat surface. Owns the [Agent] instance for the session and renders its event
 * stream into the transcript.
 *
 * Attachment flow: the system picker returns a URI, which is embedded into the user's
 * text as a directive (`read_image <uri>` / `read_file <uri>`) so the model invokes the
 * right tool — the same "give the model the tool, let it decide" shape as upstream.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<UiMessage>()

    private var agent: Agent? = null
    private var turnJob: Job? = null
    /** Index of the assistant row currently being streamed into, or -1. */
    private var streamingIndex = -1
    private var lastUserText: String = ""
    private var lastAttachment: String? = null
    private lateinit var sessionStore: SessionStore
    private lateinit var sessionAdapter: SessionAdapter
    private var sessionId: String? = null
    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Take sole ownership of inset handling before inflating (see Insets docs).
        Insets.enableEdgeToEdge(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        sessionStore = SessionStore(this)
        b.toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        b.toolbar.setNavigationOnClickListener {
            if (b.drawerLayout.isDrawerOpen(GravityCompat.START)) b.drawerLayout.closeDrawer(GravityCompat.START)
            else b.drawerLayout.openDrawer(GravityCompat.START)
        }

        // targetSdk 35 (Android 15) enforces edge-to-edge: the window draws behind the
        // 124px status bar / display cutout, so a Toolbar at y=0 renders UNDER it.
        //
        // Insets go on root_container, NOT on the Toolbar: setSupportActionBar() hands
        // padding ownership of the Toolbar to AppCompat, which resets it on layout and
        // silently discards an inset padding applied to the toolbar itself.
        Insets.padVertical(b.rootContainer)
        // The root owns system bars; the input row owns the larger IME/nav bottom.
        // Keeping these owners separate prevents the keyboard inset being added twice.
        Insets.padForIme(b.inputRow)
        ViewCompat.setOnApplyWindowInsetsListener(b.messageList) { _, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) scrollToBottom()
            insets
        }

        adapter = MessageAdapter(messages) { copyMessage(it) }
        b.messageList.layoutManager = LinearLayoutManager(this)
        b.messageList.adapter = adapter
        sessionAdapter = SessionAdapter({ loadSession(it) }, { showSessionMenu(it) })
        b.sessionList.layoutManager = LinearLayoutManager(this)
        b.sessionList.adapter = sessionAdapter
        Insets.padTop(b.drawerPanel)
        b.btnNewSession.setOnClickListener { startNewSession() }

        b.sendButton.setOnClickListener { onSendClicked() }
        b.btnImage.setOnClickListener { pickImage.launch("image/*") }
        b.btnFile.setOnClickListener { pickFile.launch("*/*") }
        b.btnTools.setOnClickListener { showToolsDialog() }
        b.btnMemory.setOnClickListener { showMemoryDialog() }

        rebuildAgent()
        refreshStatus()
        restoreLatestSession()
    }

    override fun onResume() {
        super.onResume()
        // Config may have changed in SettingsActivity.
        rebuildAgent()
        refreshStatus()
        refreshSessions()
        applyFont()
        if (messages.isEmpty() && restored) showGreeting()
    }

    // ---- agent wiring -----------------------------------------------------

    private fun rebuildAgent() {
        val cfg = ModelConfig.load(this)
        if (agent == null || turnJob?.isActive != true) {
            agent = Agent(this, cfg)
        }
    }

    private fun applyFont() {
        val font = com.nousresearch.hermes.core.FontConfig.load(this)
        b.messageInput.typeface = font.typeface()
        b.messageInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f * font.scale.multiplier)
        b.statusLine.typeface = font.typeface()
        b.statusLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f * font.scale.multiplier)
        adapter.notifyDataSetChanged()
    }

    private fun restoreLatestSession() {
        if (restored) return
        restored = true
        val saved = sessionStore.list().firstOrNull()
        if (saved == null) {
            sessionId = java.util.UUID.randomUUID().toString()
            showGreeting()
            return
        }
        sessionId = saved.id
        agent?.seed(saved.messages)
        saved.messages.mapNotNullTo(messages) { messageToUi(it) }
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun refreshSessions() = sessionAdapter.submit(sessionStore.list())

    private fun persistCurrentSession() {
        val transcript = agent?.transcript().orEmpty()
        if (transcript.isEmpty()) return
        val title = transcript.firstOrNull { it.role == "user" }?.content?.trim()
            ?.replace(Regex("\\s+"), " ")?.take(32)?.ifBlank { null } ?: getString(R.string.new_session)
        sessionStore.save(Session(sessionId ?: java.util.UUID.randomUUID().toString(), title, System.currentTimeMillis(), transcript))
        refreshSessions()
    }

    private fun messageToUi(message: ChatMessage): UiMessage? {
        val text = message.content ?: return null
        return when (message.role) {
            "user" -> UiMessage(UiMessage.Role.USER, text)
            "assistant" -> UiMessage(UiMessage.Role.ASSISTANT, text)
            "tool" -> UiMessage(UiMessage.Role.TOOL, text)
            else -> null
        }
    }

    private fun loadSession(session: Session) {
        turnJob?.cancel()
        sessionId = session.id
        agent?.seed(session.messages)
        messages.clear()
        session.messages.mapNotNullTo(messages) { messageToUi(it) }
        adapter.notifyDataSetChanged()
        b.drawerLayout.closeDrawer(GravityCompat.START)
        scrollToBottom()
    }

    private fun showSessionMenu(session: Session) {
        AlertDialog.Builder(this).setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
            if (which == 0) {
                val input = android.widget.EditText(this).apply { setText(session.title); selectAll() }
                AlertDialog.Builder(this).setTitle(R.string.rename).setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> sessionStore.rename(session.id, input.text.toString()); refreshSessions() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else AlertDialog.Builder(this).setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> sessionStore.delete(session.id); refreshSessions() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.show()
    }

    private fun startNewSession() {
        persistCurrentSession()
        agent?.reset()
        sessionId = java.util.UUID.randomUUID().toString()
        sessionStore.save(Session(sessionId!!, getString(R.string.new_session), System.currentTimeMillis(), emptyList()))
        messages.clear()
        adapter.notifyDataSetChanged()
        showGreeting()
        b.drawerLayout.closeDrawer(GravityCompat.START)
        refreshSessions()
    }

    private fun refreshStatus() {
        val cfg = ModelConfig.load(this)
        b.statusLine.text = if (!cfg.isConfigured) {
            "未配置模型 —— 点击右上角设置填写 API Key"
        } else {
            val host = runCatching { Uri.parse(cfg.baseUrl).host }.getOrNull() ?: cfg.baseUrl
            "${cfg.model} @ $host · 工具 ${ToolRegistry.available().size} · 技能 ${SkillRegistry.all(this).size}"
        }
    }

    private fun showGreeting() {
        val cfg = ModelConfig.load(this)
        val text = if (!cfg.isConfigured) {
            getString(R.string.empty_hint) + "\n\n（" + getString(R.string.need_config) + "）"
        } else {
            getString(R.string.empty_hint)
        }
        add(UiMessage(UiMessage.Role.SYSTEM, text))
    }

    // ---- sending ----------------------------------------------------------

    private fun onSendClicked() {
        if (turnJob?.isActive == true) {
            // Second tap interrupts the running turn.
            turnJob?.cancel()
            turnJob = null
            b.sendButton.text = getString(R.string.send)
            if (streamingIndex >= 0) {
                adapter.finishStreaming(streamingIndex)
                streamingIndex = -1
            }
            add(UiMessage(UiMessage.Role.SYSTEM, "已中断本次生成。"))
            return
        }

        val cfg = ModelConfig.load(this)
        if (!cfg.isConfigured) {
            add(UiMessage(UiMessage.Role.ERROR, getString(R.string.need_config)))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        var text = b.messageInput.text.toString().trim()
        val attach = lastAttachment
        if (text.isEmpty() && attach == null) return

        // Fold the attachment into the prompt as an explicit tool directive.
        if (attach != null) {
            val kind = if (attach.startsWith("image:")) "read_image" else "read_file"
            val path = attach.substringAfter(':')
            val prefix = if (kind == "read_image") {
                "[用户提供了一张图片，请先用 read_image 查看它] 路径：$path"
            } else {
                "[用户提供了一个文件，请先用 read_file 读取它] 路径：$path"
            }
            text = if (text.isEmpty()) prefix else "$prefix\n\n$text"
            lastAttachment = null
            b.btnImage.alpha = 1f
            b.btnFile.alpha = 1f
        }

        lastUserText = text
        add(UiMessage(UiMessage.Role.USER, displayTextFor(text)))
        b.messageInput.text?.clear()
        hideKeyboard()

        val a = agent ?: run {
            rebuildAgent()
            agent
        } ?: return

        turnJob = lifecycleScope.launch {
            b.sendButton.text = getString(R.string.stop)
            var sawAssistantRow = false
            try {
                a.run(text).collect { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            if (streamingIndex < 0) {
                                streamingIndex = add(
                                    UiMessage(UiMessage.Role.ASSISTANT, "", streaming = true),
                                )
                                sawAssistantRow = true
                            }
                            adapter.appendTo(streamingIndex, ev.text)
                            scrollToBottom()
                        }
                        is AgentEvent.Thinking -> Unit // reasoning is not shown inline
                        is AgentEvent.ToolStart -> {
                            if (streamingIndex >= 0) {
                                adapter.finishStreaming(streamingIndex)
                                streamingIndex = -1
                            }
                            add(
                                UiMessage(
                                    UiMessage.Role.TOOL,
                                    "▸ 调用 ${ev.name}\n${Agent.prettyArgs(ev.args)}",
                                ),
                            )
                            scrollToBottom()
                        }
                        is AgentEvent.ToolEnd -> {
                            add(
                                UiMessage(
                                    UiMessage.Role.TOOL,
                                    summarizeToolResult(ev.name, ev.result),
                                ),
                            )
                            scrollToBottom()
                        }
                        is AgentEvent.Notice -> add(UiMessage(UiMessage.Role.SYSTEM, ev.text))
                        is AgentEvent.Error -> {
                            if (streamingIndex >= 0) {
                                adapter.finishStreaming(streamingIndex)
                                streamingIndex = -1
                            }
                            add(UiMessage(UiMessage.Role.ERROR, ev.text))
                            scrollToBottom()
                        }
                        AgentEvent.TurnDone -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (streamingIndex >= 0) {
                    adapter.finishStreaming(streamingIndex)
                    streamingIndex = -1
                }
                if (t !is kotlinx.coroutines.CancellationException) {
                    add(UiMessage(UiMessage.Role.ERROR, t.message ?: t.javaClass.simpleName))
                }
            } finally {
                if (streamingIndex >= 0) {
                    adapter.finishStreaming(streamingIndex)
                    streamingIndex = -1
                }
                b.sendButton.text = getString(R.string.send)
                turnJob = null
                persistCurrentSession()
                scrollToBottom()
            }
        }
    }

    /** Show a short label for attachment-bearing prompts. */
    private fun displayTextFor(full: String): String {
        val idx = full.indexOf("]\n\n")
        return if (full.startsWith("[用户提供") && idx > 0) full.substring(idx + 3) else full
    }

    /** Tool results are bulky; show a bounded preview in the transcript. */
    private fun summarizeToolResult(name: String, result: String): String {
        val json = runCatching { org.json.JSONObject(result) }.getOrNull()
        val err = json?.optString("error").orEmpty()
        if (err.isNotEmpty()) return "✗ $name 失败：$err"
        val oneLine = result.replace(Regex("\\s+"), " ").trim()
        return "✓ $name → ${oneLine.take(300)}" + if (oneLine.length > 300) " …" else ""
    }

    // ---- attachments ------------------------------------------------------

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attach(uri, isImage = true)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attach(uri, isImage = false)
    }

    private fun attach(uri: Uri, isImage: Boolean) {
        // Persist read permission so the tool can reopen the URI during the turn.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = FileTools.displayName(this, uri) ?: uri.toString()
        lastAttachment = (if (isImage) "image:" else "file:") + uri.toString()
        val chunky = if (isImage) b.btnImage else b.btnFile
        chunky.alpha = 0.5f
        add(UiMessage(UiMessage.Role.SYSTEM, "已附加：$name（发送后交给模型处理）"))
    }

    // ---- dialogs ----------------------------------------------------------

    private fun showToolsDialog() {
        val byToolset = ToolRegistry.byToolset()
        val msg = if (byToolset.isEmpty()) "（无可用工具）"
        else byToolset.entries.joinToString("\n\n") { (ts, names) ->
            "【$ts】\n${names.joinToString("\n") { "• $it" }}"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_title)
            .setMessage(msg)
            .setPositiveButton("好", null)
            .show()
    }

    private fun showMemoryDialog() {
        val body = MemoryStore.renderJson(this)
        val pretty = runCatching { org.json.JSONObject(body).toString(2) }.getOrDefault(body)
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_title)
            .setMessage(pretty)
            .setNeutralButton(R.string.memory_clear) { _, _ ->
                MemoryStore.clear(this)
                add(UiMessage(UiMessage.Role.SYSTEM, "记忆已清空。"))
            }
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyMessage(m: UiMessage) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hermes", m.text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ---- menu / misc ------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_NEW, 0, R.string.clear)
        menu.add(0, MENU_SETTINGS, 1, R.string.settings)

        // Settings lives in the overflow; surface it with an icon too.
        val item = menu.findItem(MENU_SETTINGS)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_NEW -> {
            startNewSession()
            true
        }
        MENU_SETTINGS -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun add(m: UiMessage): Int {
        val idx = adapter.add(m)
        scrollToBottom()
        return idx
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        b.messageList.post { b.messageList.scrollToPosition(messages.lastIndex) }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.messageInput.windowToken, 0)
    }

    override fun onPause() {
        persistCurrentSession()
        super.onPause()
    }

    companion object {
        private const val MENU_NEW = 1
        private const val MENU_SETTINGS = 2
    }
}
