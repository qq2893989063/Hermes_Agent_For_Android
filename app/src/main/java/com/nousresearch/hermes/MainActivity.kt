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
import android.content.ComponentName
import android.os.IBinder
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.EditText
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
import com.nousresearch.hermes.databinding.DialogTouchControlBinding
import com.nousresearch.hermes.core.ShizukuController
import rikka.shizuku.Shizuku
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
        ShizukuController.init(this)
        Shizuku.addRequestPermissionResultListener { requestCode, _ -> if (requestCode == ShizukuController.REQUEST_CODE) invalidateOptionsMenu() }
        Shizuku.addBinderReceivedListener { invalidateOptionsMenu() }
        Shizuku.addBinderDeadListener { invalidateOptionsMenu() }
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

    override fun onDestroy() {
        ShizukuController.unbind()
        super.onDestroy()
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
        // Internal control prompts are stored as `user` messages so the provider treats them
        // as turn-taking, but they must never render: showing "[hermes-android] ..." in the
        // transcript looks like the user typed it.
        if (text.startsWith(com.nousresearch.hermes.core.ToolLoop.MARKER)) return null
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
            var delegationRow = -1
            // True once ANY row is added for this turn, so the finally-block safety net knows
            // whether the user actually saw a response.
            var sawAnyRow = false
            try {
                a.run(text).collect { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            if (streamingIndex < 0) {
                                streamingIndex = add(
                                    UiMessage(UiMessage.Role.ASSISTANT, "", streaming = true),
                                )
                                sawAnyRow = true
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
                            if (ev.name == "delegate_task") {
                                val count = runCatching { org.json.JSONObject(ev.args).optJSONArray("tasks")?.length() }.getOrNull()
                                delegationRow = add(UiMessage(UiMessage.Role.TOOL, getString(R.string.subagent_spawned) + (count?.let { " · $it" } ?: "")))
                                sawAnyRow = true
                                scrollToBottom()
                                return@collect
                            }
                            add(
                                UiMessage(
                                    UiMessage.Role.TOOL,
                                    "▸ 调用 ${ev.name}\n${Agent.prettyArgs(ev.args)}",
                                ),
                            )
                            sawAnyRow = true
                            scrollToBottom()
                        }
                        is AgentEvent.ToolEnd -> {
                            if (ev.name == "delegate_task" && delegationRow >= 0) {
                                adapter.appendTo(delegationRow, "\n" + summarizeToolResult(ev.name, ev.result))
                                delegationRow = -1
                                scrollToBottom()
                                return@collect
                            }
                            add(
                                UiMessage(
                                    UiMessage.Role.TOOL,
                                    summarizeToolResult(ev.name, ev.result),
                                ),
                            )
                            sawAnyRow = true
                            scrollToBottom()
                        }
                        is AgentEvent.Notice -> {
                            add(UiMessage(UiMessage.Role.SYSTEM, ev.text))
                            sawAnyRow = true
                        }
                        is AgentEvent.Error -> {
                            if (streamingIndex >= 0) {
                                adapter.finishStreaming(streamingIndex)
                                streamingIndex = -1
                            }
                            add(UiMessage(UiMessage.Role.ERROR, ev.text))
                            sawAnyRow = true
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
                    sawAnyRow = true
                }
            } finally {
                if (streamingIndex >= 0) {
                    adapter.finishStreaming(streamingIndex)
                    streamingIndex = -1
                }
                // Last-resort safety net: if the whole turn produced no visible row at all
                // (no assistant text, no tool row, no error), the user would stare at their
                // own message wondering whether the send worked. Always leave a trace.
                if (!sawAnyRow) {
                    add(
                        UiMessage(
                            UiMessage.Role.ERROR,
                            "模型没有返回任何可见内容，请重试或检查模型配置。",
                        ),
                    )
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

        // A delegation batch reports per-task status; surface a failed child explicitly
        // instead of burying it in the JSON, where ok=false reads like success.
        //
        // MUST be scoped to the delegating tool: web_search and web_fetch also return a
        // "results" array, whose elements (title/url/snippet) have no `ok` field. Treating
        // those as a batch reported a successful search as "⚠ web_search → 0/5 个子任务成功"
        // -- where 5 was the hit count, not a task count -- and discarded the actual results.
        if (name == "delegate_task") {
            json?.optJSONArray("results")?.let { results ->
                val total = results.length()
                val okCount = (0 until total).count {
                    results.optJSONObject(it)?.optBoolean("ok", false) == true
                }
                val secs = (0 until total).sumOf { results.optJSONObject(it)?.optLong("elapsed_ms") ?: 0L } / 1000
                if (okCount == total) return "✓ $name → $total 个子任务全部完成（累计 ${secs}s）"
                // Name the actual reason. Reporting a bare "0/1" made a timeout look like an
                // empty model response, which sent debugging in the wrong direction.
                val reasons = (0 until total).mapNotNull { i ->
                    val r = results.optJSONObject(i) ?: return@mapNotNull null
                    if (r.optBoolean("ok", false)) null
                    else r.optString("error").takeIf { it.isNotBlank() } ?: "未返回内容"
                }.distinct()
                return "⚠ $name → $okCount/$total 个子任务成功（${reasons.joinToString("；").take(160)}）"
            }
        }

        // web_search: report the query, the engine that answered, and the hit count, plus a
        // compact list of titles so the transcript shows something usable.
        if (name == "web_search") {
            json?.optJSONArray("results")?.let { results ->
                val provider = json.optString("provider").ifEmpty { "?" }
                val count = results.length()
                val titles = (0 until minOf(count, 5)).mapNotNull { i ->
                    results.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }
                }
                return buildString {
                    append("✓ $name → $count 条结果（$provider）")
                    titles.forEach { append("\n  · ").append(it.take(80)) }
                }
            }
        }

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

    private fun showTouchDialog() {
        val binding = DialogTouchControlBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.touch_control).setView(binding.root).setPositiveButton("关闭", null).create()
        fun refresh() {
            binding.tvStatus.text = getString(R.string.shizuku_status) + "：" + ShizukuController.statusText(this)
            val size = ShizukuController.screenSize()
            binding.tvScreenSize.text = size?.let { "${getString(R.string.screen_size)} ${it.first} x ${it.second}" }.orEmpty()
            val ready = ShizukuController.isPermissionGranted()
            binding.tvHint.text = if (!ShizukuController.isAvailable()) getString(R.string.shizuku_install_hint) else ""
            listOf(binding.btnTap, binding.btnSwipe, binding.btnKey).forEach { it.isEnabled = ready }
            binding.btnPermission.isEnabled = ShizukuController.isAvailable()
            if (ready) ShizukuController.bind()
        }
        binding.btnPermission.setOnClickListener { if (ShizukuController.isAvailable()) ShizukuController.requestPermission(); refresh() }
        binding.btnTap.setOnClickListener { coordinateDialog("点击") { x, y -> binding.tvResult.text = ShizukuController.tap(x, y) } }
        binding.btnSwipe.setOnClickListener { swipeDialog { a, c, d, e, dur -> binding.tvResult.text = ShizukuController.swipe(a, c, d, e, dur) } }
        binding.btnKey.setOnClickListener { AlertDialog.Builder(this).setTitle(R.string.touch_key).setItems(arrayOf("返回", "Home", "最近任务", "电源")) { _, which -> binding.tvResult.text = ShizukuController.keyevent(intArrayOf(4, 3, 187, 26)[which]) }.show() }
        dialog.setOnShowListener { refresh() }
        dialog.show()
    }

    private fun coordinateDialog(title: String, action: (Int, Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val x = EditText(this).apply { hint = "X"; inputType = 2 }; val y = EditText(this).apply { hint = "Y"; inputType = 2 }
        box.addView(x); box.addView(y)
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("执行") { _, _ -> val a=x.text.toString().toIntOrNull(); val c=y.text.toString().toIntOrNull(); if(a!=null&&c!=null) action(a,c) }.setNegativeButton("取消", null).show()
    }

    private fun swipeDialog(action: (Int, Int, Int, Int, Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val fields = (1..5).map { EditText(this).apply { hint = arrayOf("X1","Y1","X2","Y2","时长(ms)")[it-1]; inputType = 2 } }; fields.forEach(box::addView)
        AlertDialog.Builder(this).setTitle(R.string.touch_swipe).setView(box).setPositiveButton("执行") { _, _ -> val v=fields.map { it.text.toString().toIntOrNull() }; if(v.all { it!=null }) action(v[0]!!,v[1]!!,v[2]!!,v[3]!!,v[4]!!) }.setNegativeButton("取消", null).show()
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
        menu.add(0, MENU_TOUCH, 2, R.string.touch_control)

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
        MENU_TOUCH -> { showTouchDialog(); true }
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
        private const val MENU_TOUCH = 3
    }
}
