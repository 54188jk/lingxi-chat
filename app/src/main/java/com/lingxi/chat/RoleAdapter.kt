package com.lingxi.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.data.RolePreset

class RoleAdapter(
    private val roles: List<RolePreset>,
    private val activeId: String?,
    private val onClick: (RolePreset) -> Unit,
    private val onLongClick: (RolePreset) -> Unit
) : RecyclerView.Adapter<RoleAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = roles.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = roles[position]
        holder.name.text = r.name
        holder.detail.text = r.prompt
        holder.badge.visibility = if (r.id == activeId) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(r) }
        holder.itemView.setOnLongClickListener { onLongClick(r); true }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvModelName)
        val detail: TextView = v.findViewById(R.id.tvModelDetail)
        val badge: TextView = v.findViewById(R.id.tvActiveBadge)
    }
}
