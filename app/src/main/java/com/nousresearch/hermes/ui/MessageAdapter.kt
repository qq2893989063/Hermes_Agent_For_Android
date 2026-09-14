package com.nousresearch.hermes.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.hermes.R

/** One row in the transcript. */
data class UiMessage(
    val role: Role,
    var text: String,
    var streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM, ERROR }
}

/**
 * Transcript adapter. Renders user / assistant / tool / system rows with distinct
 * colours, and appends streamed text in place while a turn is in flight.
 */
class MessageAdapter(
    private val items: MutableList<UiMessage>,
    private val onCopy: (UiMessage) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val role: TextView = view.findViewById(R.id.msg_role)
        val body: TextView = view.findViewById(R.id.msg_body)
        val card: View = view.findViewById(R.id.msg_card)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context
        holder.role.text = label(m)
        holder.body.text = m.text
        holder.body.setTextColor(ctx.getColor(colorFor(m)))
        holder.card.setBackgroundResource(backgroundFor(m))
        holder.card.setOnLongClickListener {
            onCopy(m)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun label(m: UiMessage): String = when (m.role) {
        UiMessage.Role.USER -> "你"
        UiMessage.Role.ASSISTANT -> if (m.streaming) "Hermes · 生成中…" else "Hermes"
        UiMessage.Role.TOOL -> "工具"
        UiMessage.Role.SYSTEM -> "系统"
        UiMessage.Role.ERROR -> "错误"
    }

    private fun colorFor(m: UiMessage): Int = when (m.role) {
        UiMessage.Role.USER -> R.color.hermes_text
        UiMessage.Role.ASSISTANT -> R.color.hermes_text
        UiMessage.Role.TOOL -> R.color.hermes_muted
        UiMessage.Role.SYSTEM -> R.color.hermes_muted
        UiMessage.Role.ERROR -> R.color.hermes_error
    }

    private fun backgroundFor(m: UiMessage): Int = when (m.role) {
        UiMessage.Role.USER -> R.drawable.bg_bubble_user
        UiMessage.Role.ASSISTANT -> R.drawable.bg_bubble_assistant
        else -> R.drawable.bg_bubble_meta
    }

    // ---- mutation ---------------------------------------------------------

    fun add(m: UiMessage): Int {
        items.add(m)
        notifyItemInserted(items.lastIndex)
        return items.lastIndex
    }

    /** Appends streamed text into the row at [index] without re-binding the whole list. */
    fun appendTo(index: Int, delta: String) {
        if (index !in items.indices) return
        val m = items[index]
        m.text = m.text + delta
        notifyItemChanged(index)
    }

    fun replaceAt(index: Int, m: UiMessage) {
        if (index !in items.indices) return
        items[index] = m
        notifyItemChanged(index)
    }

    fun finishStreaming(index: Int) {
        if (index !in items.indices) return
        if (!items[index].streaming) return
        items[index] = items[index].copy(streaming = false)
        notifyItemChanged(index)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun snapshot(): List<UiMessage> = items.toList()
}
