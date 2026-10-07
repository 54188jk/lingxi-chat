package com.lingxi.chat

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.lingxi.chat.net.NoticeClient

/**
 * 公告：仓库 ANNOUNCEMENT.md 拉取，三源轮换、免 Token。
 *
 * 启动静默拉一次，内容有变化才弹卡片（用指纹比对，一天最多弹一次），
 * 顶栏铃铛随时可以手动查看。
 */
object NoticeUi {

    private const val PREF = "lingxi_notice"
    private const val KEY_HASH = "seen_hash"
    private const val KEY_SHOWN_DAY = "shown_day"

    private fun prefs(activity: Activity) =
        activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)

    /** 顶栏铃铛红点：有没有没看过的公告 */
    fun hasUnread(activity: Activity): Boolean {
        val cached = prefs(activity).getString(KEY_HASH, "") ?: ""
        val latest = prefs(activity).getString(KEY_HASH + "_latest", "") ?: ""
        return cached.isNotBlank() && cached != latest
    }

    fun markRead(activity: Activity) {
        val p = prefs(activity)
        p.edit().putString(KEY_HASH, p.getString(KEY_HASH + "_latest", "")).apply()
    }

    /** 启动时静默检查：有新公告就弹一次 */
    fun checkOnStart(activity: Activity) {
        Thread {
            val notice = NoticeClient.fetch() ?: return@Thread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val p = prefs(activity)
                p.edit().putString(KEY_HASH + "_latest", notice.hash).apply()
                val seen = p.getString(KEY_HASH, "")
                if (seen == notice.hash) return@runOnUiThread
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                    .format(java.util.Date())
                if (p.getString(KEY_SHOWN_DAY, "") == today) return@runOnUiThread
                p.edit().putString(KEY_SHOWN_DAY, today).apply()
                show(activity, notice, onRead = { markRead(activity) })
            }
        }.start()
    }

    /** 铃铛点击：手动查看 */
    fun open(activity: Activity) {
        show(activity, null, onRead = { markRead(activity) })
    }

    private fun show(activity: Activity, preset: NoticeClient.Notice?, onRead: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_notice, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvNoticeTitle)
        val tvTime = view.findViewById<TextView>(R.id.tvNoticeTime)
        val tvBody = view.findViewById<TextView>(R.id.tvNoticeBody)
        val tvLink = view.findViewById<TextView>(R.id.tvNoticeLink)

        fun fill(n: NoticeClient.Notice) {
            tvTitle.text = n.title
            tvTime.text = if (n.publishedAt.isNotBlank()) "发布于 ${n.publishedAt}" else "官方公告"
            tvBody.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            tvBody.text = MarkdownRenderer.render(
                view.context, n.body,
                onLinkClick = { url ->
                    try {
                        activity.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(if (url.startsWith("http")) url else "https://$url")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("我知道了") { _, _ -> onRead() }
            .create()

        if (preset != null) {
            fill(preset)
        } else {
            tvTitle.text = "公告"
            tvTime.text = "正在获取…"
            tvBody.text = ""
            dialog.show()
            Thread {
                val n = NoticeClient.fetch()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (n == null) {
                        tvTitle.text = "公告"
                        tvTime.text = "获取失败"
                        tvBody.text = "暂时无法获取公告，请稍后再试或检查网络。"
                    } else {
                        fill(n)
                        prefs(activity).edit().putString(KEY_HASH + "_latest", n.hash).apply()
                    }
                }
            }.start()
            return
        }
        dialog.show()
    }

    /** 顶栏铃铛按钮（含未读红点） */
    fun bindBell(activity: Activity, bell: ImageView) {
        Thread {
            val n = NoticeClient.fetch() ?: return@Thread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val p = prefs(activity)
                p.edit().putString(KEY_HASH + "_latest", n.hash).apply()
                val unread = p.getString(KEY_HASH, "") != n.hash
                bell.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(
                        activity,
                        if (unread) R.color.accent else R.color.icon_tint
                    ),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        }.start()
    }
}