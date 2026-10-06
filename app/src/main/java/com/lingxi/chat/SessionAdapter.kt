package com.lingxi.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.data.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val sessions: List<Session>,
    private val onClick: (Session) -> Unit,
    private val onLongClick: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.Holder>() {

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = sessions.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = sessions[position]
        holder.title.text = s.title.ifBlank { "未命名会话" }
        holder.meta.text = "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条消息"
        holder.itemView.setOnClickListener { onClick(s) }
        holder.itemView.setOnLongClickListener { onLongClick(s); true }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvSessionTitle)
        val meta: TextView = v.findViewById(R.id.tvSessionMeta)
    }
}
