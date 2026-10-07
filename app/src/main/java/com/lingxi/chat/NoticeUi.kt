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
    private const val KEY_FETCH_AT = "fetch_at"
    private const val KEY_CACHE = "cache"
    private const val THROTTLE_MS = 10 * 60 * 1000L

    private fun prefs(activity: Activity) =
        activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)

    private fun cachePrefs(activity: Activity) =
        activity.getSharedPreferences(KEY_CACHE, Activity.MODE_PRIVATE)

    private fun ensureCache(activity: Activity) {
        com.lingxi.chat.net.NoticeClient.initCache(cachePrefs(activity))
    }

    private fun persist(activity: Activity, notice: com.lingxi.chat.net.NoticeClient.Notice) {
        com.lingxi.chat.net.NoticeClient.saveCache(
            cachePrefs(activity), notice, com.lingxi.chat.net.NoticeClient.lastEtag()
        )
    }

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

    /** 启动时静默检查：先秒开缓存，再后台刷新；有新公告才弹卡片 */
    fun checkOnStart(activity: Activity) {
        ensureCache(activity)
        // 缓存里就有未读的新公告 → 立刻弹，不用等网络
        com.lingxi.chat.net.NoticeClient.cached()?.let { cached ->
            val p = prefs(activity)
            if ((p.getString(KEY_HASH, "") ?: "") != cached.hash &&
                (p.getString(KEY_SHOWN_DAY, "") ?: "") != today()
            ) {
                p.edit().putString(KEY_SHOWN_DAY, today()).apply()
                show(activity, cached, onRead = { markRead(activity) })
            }
        }
        // 10 分钟内已拉过就不再打扰服务端
        val p = prefs(activity)
        if (System.currentTimeMillis() - (p.getLong(KEY_FETCH_AT, 0L)) < THROTTLE_MS) return

        Thread {
            val result = com.lingxi.chat.net.NoticeClient.load()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                prefs(activity).edit()
                    .putLong(KEY_FETCH_AT, System.currentTimeMillis())
                    .apply()
                val n = result?.notice ?: return@runOnUiThread
                if (!result.fromCache) persist(activity, n)
                prefs(activity).edit().putString(KEY_HASH + "_latest", n.hash).apply()
                val seen = prefs(activity).getString(KEY_HASH, "") ?: ""
                if (seen == n.hash) return@runOnUiThread
                if (result.fromCache) return@runOnUiThread // 缓存那次已经弹过了
                val sp2 = prefs(activity)
                if ((sp2.getString(KEY_SHOWN_DAY, "") ?: "") == today()) return@runOnUiThread
                sp2.edit().putString(KEY_SHOWN_DAY, today()).apply()
                show(activity, n, onRead = { markRead(activity) })
            }
        }.start()
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())

    /** 铃铛点击：先秒显缓存，再拉最新 */
    fun open(activity: Activity) {
        ensureCache(activity)
        show(activity, com.lingxi.chat.net.NoticeClient.cached(), onRead = { markRead(activity) })
    }

    private fun show(activity: Activity, preset: NoticeClient.Notice?, onRead: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_notice, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvNoticeTitle)
        val tvTime = view.findViewById<TextView>(R.id.tvNoticeTime)
        val tvBody = view.findViewById<TextView>(R.id.tvNoticeBody)
        val tvMore = view.findViewById<TextView>(R.id.tvNoticeMore)
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.svNotice)
        var full: CharSequence = ""
        var expanded = false

        fun renderFull(n: NoticeClient.Notice): CharSequence =
            MarkdownRenderer.render(
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

        fun fill(n: NoticeClient.Notice) {
            tvTitle.text = n.title
            tvTime.text = if (n.publishedAt.isNotBlank()) "发布于 ${n.publishedAt}" else "官方公告"
            tvBody.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            full = renderFull(n)
            expanded = false
            tvMore.text = "展开完整内容"
            // 内容长时先折叠，小屏/老设备不会被一屏文字糊住
            tvBody.text = if (n.body.length > 300) {
                full.subSequence(0, 300).toString() + "\n…"
            } else {
                full
            }
            tvMore.visibility = if (n.body.length > 300) View.VISIBLE else View.GONE
        }

        tvMore.setOnClickListener {
            expanded = !expanded
            tvMore.text = if (expanded) "收起" else "展开完整内容"
            tvBody.text = if (expanded) full else {
                full.subSequence(0, 300).toString() + "\n…"
            }
            if (expanded) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
                        tvTime.text = "暂无公告"
                        tvBody.text = "官方暂未发布公告内容。"
                        tvMore.visibility = View.GONE
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

    /** 顶栏铃铛：有未读公告染强调色。缓存即可判断，不阻塞、不联网 */
    fun bindBell(activity: Activity, bell: android.widget.ImageView) {
        ensureCache(activity)
        val unread = hasUnread(activity)
        bell.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                activity,
                if (unread) R.color.accent else R.color.icon_tint
            ),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }
}