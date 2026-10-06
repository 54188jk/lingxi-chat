package com.lingxi.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.data.ModelConfig

class ModelAdapter(
    private val models: List<ModelConfig>,
    private val activeId: String?,
    private val onClick: (ModelConfig) -> Unit,
    private val onLongClick: (ModelConfig) -> Unit
) : RecyclerView.Adapter<ModelAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = models.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = models[position]
        holder.name.text = m.name
        holder.detail.text = "${m.model} · ${m.baseUrl}"
        holder.badge.visibility = if (m.id == activeId) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(m) }
        holder.itemView.setOnLongClickListener { onLongClick(m); true }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvModelName)
        val detail: TextView = v.findViewById(R.id.tvModelDetail)
        val badge: TextView = v.findViewById(R.id.tvActiveBadge)
    }
}
