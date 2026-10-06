package com.lingxi.chat

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.data.ChatMessage

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onLongPress: (ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
    }

    var streamingIndex = -1

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].role == "user") TYPE_USER else TYPE_AI
    }

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserHolder(inf.inflate(R.layout.item_message_user, parent, false))
        } else {
            AiHolder(inf.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserHolder) {
            holder.tv.text = msg.content.ifBlank { "[图片]" }
            if (msg.imageBase64 != null) {
                try {
                    val bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.iv.setImageBitmap(bmp)
                    holder.iv.visibility = View.VISIBLE
                } catch (e: Exception) {
                    holder.iv.visibility = View.GONE
                }
            } else {
                holder.iv.visibility = View.GONE
            }
            holder.itemView.setOnLongClickListener { onLongPress(msg); true }
        } else if (holder is AiHolder) {
            holder.tv.text = when {
                position == streamingIndex && msg.content.isBlank() -> "思考中…"
                position == streamingIndex -> android.text.TextUtils.concat(MarkdownRenderer.render(holder.tv.context, msg.content), " ▍")
                else -> MarkdownRenderer.render(holder.tv.context, msg.content)
            }
            holder.itemView.setOnLongClickListener { onLongPress(msg); true }
        }
    }

    class UserHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvMsg)
        val iv: ImageView = v.findViewById(R.id.ivMsgImage)
    }

    class AiHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvMsg)
    }
}
