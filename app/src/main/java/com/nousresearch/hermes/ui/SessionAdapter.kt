package com.nousresearch.hermes.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.hermes.R
import com.nousresearch.hermes.core.Session
import java.text.DateFormat
import java.util.Date

class SessionAdapter(private val onClick: (Session) -> Unit, private val onLongClick: (Session) -> Unit) : RecyclerView.Adapter<SessionAdapter.VH>() {
    private var items = emptyList<Session>()
    class VH(v: View) : RecyclerView.ViewHolder(v) { val title: TextView = v.findViewById(R.id.session_title); val time: TextView = v.findViewById(R.id.session_time) }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_session, p, false))
    override fun onBindViewHolder(h: VH, i: Int) { val s = items[i]; h.title.text = s.title; h.time.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.updatedAt)); h.itemView.setOnClickListener { onClick(s) }; h.itemView.setOnLongClickListener { onLongClick(s); true } }
    override fun getItemCount() = items.size
    fun submit(sessions: List<Session>) { items = sessions; notifyDataSetChanged() }
}
