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
import com.lingxi.chat.databinding.ItemMessageAiBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onLongPress: (ChatMessage) -> Unit,
    private val onCopyCode: (String) -> Unit = { },
    private val onOpenLink: (String) -> Unit = { }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val mdFmt = SimpleDateFormat("M月d日", Locale.getDefault())
    }

    var streamingIndex = -1
    var thinkingDots = 0

    /** 会话内搜索命中的关键词，非空时在气泡里标黄 */
    var highlight: String? = null

    /** 正在流式输出的那条气泡，打字机直接改它，避免频繁 notifyItemChanged */
    private var streamHolder: AiHolder? = null

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == "user") TYPE_USER else TYPE_AI

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val maxW = (parent.resources.displayMetrics.widthPixels * 0.78f).toInt()
        return if (viewType == TYPE_USER) {
            val h = UserHolder(inf.inflate(R.layout.item_message_user, parent, false))
            h.tv.maxWidth = maxW
            h
        } else {
            val binding = ItemMessageAiBinding.inflate(inf, parent, false)
            binding.tvMsg.maxWidth = maxW
            AiHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val dateText = if (position == 0 || !sameDay(msg.time, messages[position - 1].time)) {
            dateLabel(msg.time)
        } else {
            null
        }

        if (holder is UserHolder) {
            holder.time.text = timeFmt.format(Date(msg.time))
            holder.date.text = dateText.orEmpty()
            holder.date.visibility = if (dateText != null) View.VISIBLE else View.GONE
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
            holder.itemView.startPopIn()
            return
        }

        if (holder is AiHolder) {
            val b = holder.binding
            b.tvMsg.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            b.tvMsg.text = renderMsg(b.tvMsg.context, msg, position == streamingIndex)
            b.tvTime.text = timeFmt.format(Date(msg.time))
            b.tvDate.text = dateText.orEmpty()
            b.tvDate.visibility = if (dateText != null) View.VISIBLE else View.GONE
            bindSources(b, msg)
            b.tvMsg.setOnLongClickListener { onLongPress(msg); true }
            b.root.setOnLongClickListener { onLongPress(msg); true }
            b.root.startPopIn()
            if (position == streamingIndex) streamHolder = holder
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder === streamHolder) streamHolder = null
        super.onViewRecycled(holder)
    }

    private fun renderMsg(ctx: android.content.Context, msg: ChatMessage, streaming: Boolean): CharSequence {
        val base = MarkdownRenderer.render(
            ctx, msg.content,
            highlight = highlight,
            onCodeClick = { onCopyCode(it) },
            onLinkClick = { onOpenLink(it) }
        )
        return when {
            streaming && msg.content.isBlank() -> "思考中" + "·".repeat(thinkingDots)
            streaming -> android.text.TextUtils.concat(base, " ▍")
            else -> base
        }
    }

    /** 打字机：直接改当前可见气泡的文本，不触发重新绑定。[caret] 控制流式光标显隐 */
    fun updateStreamingText(displayText: String, caret: Boolean = true) {
        val h = streamHolder ?: return
        try {
            val rendered = MarkdownRenderer.render(
                h.binding.tvMsg.context, displayText,
                highlight = highlight,
                onCodeClick = { onCopyCode(it) },
                onLinkClick = { onOpenLink(it) }
            )
            h.binding.tvMsg.text =
                if (caret) android.text.TextUtils.concat(rendered, " ▍") else rendered
        } catch (_: Exception) {
        }
    }

    /** 收尾：全量重绘一次 */
    fun flushStreaming(position: Int) {
        streamHolder = null
        notifyItemChanged(position)
    }

    private fun bindSources(b: ItemMessageAiBinding, msg: ChatMessage) {
        val box = b.llSources
        box.removeAllViews()
        if (msg.sources.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        val ctx = b.tvMsg.context
        val secondary = androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
        val accent = androidx.core.content.ContextCompat.getColor(ctx, R.color.accent)
        msg.sources.forEachIndexed { i, s ->
            val tv = TextView(ctx)
            tv.text = "[${i + 1}] ${s.title}"
            tv.textSize = 11f
            tv.setTextColor(accent)
            tv.setPadding(0, 4, 0, 4)
            tv.maxLines = 2
            tv.setOnClickListener { onOpenLink(s.url) }
            box.addView(tv)
        }
        val hint = TextView(ctx)
        hint.text = "参考来源（点标题打开）"
        hint.textSize = 10f
        hint.setTextColor(secondary)
        box.addView(hint, 0)
        box.visibility = View.VISIBLE
    }

    /** 新消息上浮淡入；系统关掉动画时直接跳过 */
    private fun View.startPopIn() {
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (scale <= 0.01f) return
        alpha = 0f
        translationY = 12f * resources.displayMetrics.density
        animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun sameDay(t1: Long, t2: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun dateLabel(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val sameYear = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        return when {
            sameYear && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
            sameYear && cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "昨天"
            sameYear -> mdFmt.format(Date(t))
            else -> dayFmt.format(Date(t))
        }
    }

    class UserHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvMsg)
        val iv: ImageView = v.findViewById(R.id.ivMsgImage)
        val time: TextView = v.findViewById(R.id.tvTime)
        val date: TextView = v.findViewById(R.id.tvDate)
    }

    class AiHolder(val binding: ItemMessageAiBinding) : RecyclerView.ViewHolder(binding.root)
}