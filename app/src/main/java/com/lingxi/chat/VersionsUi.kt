package com.lingxi.chat

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.net.UpdateChecker
import java.util.Locale

/** 历史版本列表：列出所有 Release，点「下载」走和更新一样的镜像加速与安装流程 */
object VersionsUi {

    fun open(activity: Activity, currentVersion: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_versions, null)
        val state = view.findViewById<TextView>(R.id.tvVersionsState)
        val list = view.findViewById<RecyclerView>(R.id.rvVersions)
        list.layoutManager = LinearLayoutManager(activity)

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()

        Thread {
            val releases = UpdateChecker.fetchReleases()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (releases.isEmpty()) {
                    state.text = "获取失败，请检查网络后重试"
                    state.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                state.visibility = View.GONE
                list.adapter = VersionAdapter(activity, releases, currentVersion)
            }
        }.start()
    }

    private class VersionAdapter(
        private val activity: Activity,
        private val items: List<UpdateChecker.ReleaseInfo>,
        private val currentVersion: String
    ) : RecyclerView.Adapter<VersionAdapter.Holder>() {

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
            }
            val note = info.notes
                .lines()
                .filterNot { it.trim().startsWith("发布时间") || it.trim().startsWith("#") }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
            holder.note.text = if (note.isBlank()) "（无更新说明）" else note
            holder.btn.isEnabled = !isCurrent
            holder.btn.alpha = if (isCurrent) 0.4f else 1f
            holder.btn.setOnClickListener {
                val downgrade = !UpdateChecker.isNewer(info.version, currentVersion) &&
                        !isCurrent
                UpdateUi.openVersionPage(activity, info, downgrade)
            }
        }
    }
}