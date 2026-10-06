package com.lingxi.chat

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.lingxi.chat.net.UpdateChecker

object UpdateUi {

    fun check(activity: Activity, currentVersion: String, silent: Boolean) {
        if (!silent) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val info = UpdateChecker.fetchLatest()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    info == null -> {
                        if (!silent) Toast.makeText(activity, "检查失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    }
                    UpdateChecker.isNewer(info.version, currentVersion) -> {
                        showUpdateDialog(activity, info)
                    }
                    else -> {
                        if (!silent) Toast.makeText(activity, "已是最新版本（v$currentVersion）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateChecker.ReleaseInfo) {
        val notes = info.notes.ifBlank { "修复已知bug" }
        android.app.AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(notes)
            .setPositiveButton("下载更新") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.downloadUrl)))
                } catch (e: Exception) {
                    Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }
}
