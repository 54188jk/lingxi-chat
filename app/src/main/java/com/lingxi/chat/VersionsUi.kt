package com.lingxi.chat

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.net.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 历史版本列表：列出所有 Release，点「下载」走和更新一样的镜像加速与安装流程 */
object VersionsUi {

    fun open(activity: Activity, currentVersion: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_versions, null)
        val state = view.findViewById<TextView>(R.id.tvVersionsState)
        val list = view.findViewById<RecyclerView>(R.id.rvVersions)
        list.layoutManager = LinearLayoutManager(activity)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()

        val owner = activity as? LifecycleOwner ?: return
        val job = owner.lifecycleScope.launch {
            val releases = withContext(Dispatchers.IO) { UpdateChecker.fetchReleases() }
            if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@launch
            if (releases.isEmpty()) {
                state.text = "获取失败，请检查网络后重试"
                state.visibility = View.VISIBLE
                return@launch
            }
            state.visibility = View.GONE
            list.adapter = VersionAdapter(activity, releases, currentVersion)
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private class VersionAdapter(
        private val activity: Activity,
        private val items: List<UpdateChecker.ReleaseInfo>,
        private val currentVersion: String
    ) : RecyclerView.Adapter<VersionAdapter.Holder>() {

        /** 本机已经存过安装包的那些版本，列表上要标出来 */
        private val archived =
            com.lingxi.chat.data.HistoryStore.list(activity).map { it.version }.toSet()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvVerName)
            val meta: TextView = v.findViewById(R.id.tvVerMeta)
            val note: TextView = v.findViewById(R.id.tvVerNote)
            val btn: Button = v.findViewById(R.id.btnDownloadVer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_version, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val info = items[position]
            val isCurrent = info.version == currentVersion
            holder.name.text = if (isCurrent) "v${info.version}（当前）" else "v${info.version}"
            holder.meta.text = buildString {
                if (info.publishedAt.isNotBlank()) append(info.publishedAt)
                if (info.sizeBytes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("%.1f MB".format(Locale.US, info.sizeBytes / 1024.0 / 1024.0))
                }
                if (archived.contains(info.version)) {
                    if (isNotEmpty()) append(" · ")
                    append("本机已存安装包")
                }
            }
            val note = plainNote(info)
            holder.note.text = if (note.isBlank()) "（无更新说明）" else note
            holder.btn.isEnabled = !isCurrent
            holder.btn.alpha = if (isCurrent) 0.4f else 1f

            val downgrade = !isCurrent && !UpdateChecker.isNewer(info.version, currentVersion)
            // 点整行看该版本完整更新内容
            holder.itemView.setOnClickListener {
                showNotes(activity, info, isCurrent, downgrade)
            }
            // 点下载按钮直接进下载页
            holder.btn.setOnClickListener {
                if (isCurrent) return@setOnClickListener
                UpdateUi.openVersionPage(activity, info, downgrade)
            }
        }
    }

    /** 把 Release 说明压成一行摘要 */
    private fun plainNote(info: UpdateChecker.ReleaseInfo): String =
        info.notes
            .lines()
            .filterNot { it.trim().startsWith("发布时间") || it.trim().startsWith("#") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 弹窗展示某个版本的完整更新内容 */
    private fun showNotes(
        activity: Activity,
        info: UpdateChecker.ReleaseInfo,
        isCurrent: Boolean,
        downgrade: Boolean
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_version_notes, null)
        view.findViewById<TextView>(R.id.tvVersionTitle).text =
            if (isCurrent) "v${info.version}（当前版本）" else "v${info.version}"
        view.findViewById<TextView>(R.id.tvVersionMeta).text = buildString {
            if (info.publishedAt.isNotBlank()) append("发布于 ${info.publishedAt}")
            if (info.sizeBytes > 0) {
                if (isNotEmpty()) append(" · ")
                append("%.1f MB".format(Locale.US, info.sizeBytes / 1024.0 / 1024.0))
            }
            if (downgrade) append(" · 历史版本")
        }
        val tvNotes = view.findViewById<TextView>(R.id.tvVersionNotes)
        tvNotes.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        val body = info.notes
            .lines()
            .filterNot { it.trim().startsWith("发布时间") }
            .joinToString("\n")
            .trim()
        tvNotes.text = MarkdownRenderer.render(
            view.context,
            body.ifBlank { "（该版本没有留下更新说明）" },
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

        val tvLink = view.findViewById<TextView>(R.id.tvVersionLink)
        tvLink.setOnClickListener {
            try {
                activity.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(info.pageUrl)
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(activity, "无法打开页面", Toast.LENGTH_SHORT).show()
            }
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
        if (!isCurrent) {
            builder.setPositiveButton(if (downgrade) "回退到此版本" else "下载此版本") { _, _ ->
                UpdateUi.openVersionPage(activity, info, downgrade)
            }
        }
        builder.show()
    }
}